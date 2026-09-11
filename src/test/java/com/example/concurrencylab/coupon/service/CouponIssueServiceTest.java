package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.repository.CouponIssueRepository;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CouponIssueService 단위 테스트 (Mockito). Redis/DB 없이 issue()/rebuildRedisState() 의
 * 분기 로직만 검증한다. Lua 스크립트의 실제 원자성·동시성 결과는 CouponConcurrencyTest,
 * 장애 복구 시나리오는 CouponRedisRecoveryTest(둘 다 통합 테스트) 로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueServiceTest {

    @Mock
    CouponRepository couponRepository;
    @Mock
    CouponIssueRepository couponIssueRepository;
    @Mock
    CouponIssueProcessor couponIssueProcessor;
    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    ValueOperations<String, String> valueOperations;
    @Mock
    SetOperations<String, String> setOperations;

    CouponIssueService couponIssueService;

    @BeforeEach
    void setUp() {
        couponIssueService = new CouponIssueService(
                couponRepository, couponIssueRepository, couponIssueProcessor, redisTemplate);
        couponIssueService.loadScript(); // @PostConstruct 를 테스트에서 수동 호출 (실제 lua 리소스 로딩)
    }

    private void givenScriptReturns(Long code) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any())).willReturn(code);
    }

    @Test
    @DisplayName("createCoupon: DB 저장 후 Redis 재고를 SET 하고 발급집합은 비운다")
    void createCoupon_seedsRedis() {
        Coupon saved = mock(Coupon.class);
        given(saved.getId()).willReturn(1L);
        given(couponRepository.save(any(Coupon.class))).willReturn(saved);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        Long id = couponIssueService.createCoupon("쿠폰", 100);

        assertThat(id).isEqualTo(1L);
        verify(valueOperations).set("coupon:stock:1", "100");
        verify(redisTemplate).delete("coupon:issued:1");
    }

    @Test
    @DisplayName("issue: Lua 가 1(발급가능) 반환 시 DB 기록을 위임한다")
    void issue_success_delegatesToProcessor() {
        givenScriptReturns(1L);

        couponIssueService.issue(1L, 100L);

        verify(couponIssueProcessor).persistIssue(1L, 100L);
    }

    @Test
    @DisplayName("issue: Lua 가 0(소진) 반환 시 IllegalStateException, DB 는 건드리지 않는다")
    void issue_soldOut() {
        givenScriptReturns(0L);

        assertThatThrownBy(() -> couponIssueService.issue(1L, 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("소진");

        verifyNoInteractions(couponIssueProcessor);
    }

    @Test
    @DisplayName("issue: Lua 가 -1(이미 발급) 반환 시 IllegalStateException")
    void issue_duplicate() {
        givenScriptReturns(-1L);

        assertThatThrownBy(() -> couponIssueService.issue(1L, 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 발급받은");

        verifyNoInteractions(couponIssueProcessor);
    }

    @Test
    @DisplayName("issue: Lua 가 -2(쿠폰 없음) 반환 시 IllegalArgumentException")
    void issue_couponNotFound() {
        givenScriptReturns(-2L);

        assertThatThrownBy(() -> couponIssueService.issue(1L, 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("issue: Redis 응답이 null 이면 쿠폰 없음(-2)으로 취급한다")
    void issue_nullResult_treatedAsNotFound() {
        givenScriptReturns(null);

        assertThatThrownBy(() -> couponIssueService.issue(1L, 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("issue: DB 기록 실패 시 Redis 를 보상(재고 복원 + 발급집합 제거)하고 예외를 다시 던진다")
    void issue_dbFailure_compensatesRedis() {
        givenScriptReturns(1L);
        doThrow(new IllegalStateException("이미 발급받은 쿠폰입니다. userId=100"))
                .when(couponIssueProcessor).persistIssue(1L, 100L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);

        assertThatThrownBy(() -> couponIssueService.issue(1L, 100L))
                .isInstanceOf(IllegalStateException.class);

        verify(valueOperations).increment("coupon:stock:1");
        verify(setOperations).remove("coupon:issued:1", "100");
    }

    @Test
    @DisplayName("rebuildRedisState: 쿠폰이 없으면 IllegalArgumentException, Redis 는 건드리지 않는다")
    void rebuild_couponNotFound() {
        given(couponRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> couponIssueService.rebuildRedisState(1L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(setOperations, valueOperations);
    }

    @Test
    @DisplayName("rebuildRedisState: 발급 이력으로 발급집합을 채우고, 남은 재고(총량-이력수)를 SET한다")
    void rebuild_reconstructsFromHistory() {
        Coupon coupon = mock(Coupon.class);
        given(coupon.getTotalQuantity()).willReturn(10);
        given(couponRepository.findById(1L)).willReturn(Optional.of(coupon));
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(11L, 22L, 33L));
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        couponIssueService.rebuildRedisState(1L);

        verify(redisTemplate).delete("coupon:issued:1");
        verify(setOperations).add("coupon:issued:1", "11", "22", "33");
        verify(valueOperations).set("coupon:stock:1", "7"); // 10 - 3
    }

    @Test
    @DisplayName("rebuildRedisState: 발급 이력이 없으면 발급집합에 SADD 하지 않고, 재고는 총량 그대로 SET한다")
    void rebuild_noHistory_skipsSaddButSetsFullStock() {
        Coupon coupon = mock(Coupon.class);
        given(coupon.getTotalQuantity()).willReturn(5);
        given(couponRepository.findById(1L)).willReturn(Optional.of(coupon));
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        couponIssueService.rebuildRedisState(1L);

        verify(redisTemplate).delete("coupon:issued:1");
        verify(setOperations, never()).add(anyString(), anyString());
        verify(valueOperations).set("coupon:stock:1", "5");
    }
}
