package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.CouponIssue;
import com.example.concurrencylab.coupon.repository.CouponIssueRepository;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CouponIssueProcessor 단위 테스트 (Mockito). DB/Redis 없이 persistIssue() 의 분기만 검증한다.
 * 실제 DB 유니크 제약 동작은 CouponConcurrencyTest(통합) 로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueProcessorTest {

    @Mock
    CouponRepository couponRepository;
    @Mock
    CouponIssueRepository couponIssueRepository;

    @InjectMocks
    CouponIssueProcessor couponIssueProcessor;

    @Test
    @DisplayName("increaseIssuedCount 영향 행 수 0 -> 쿠폰 없음(IllegalArgumentException), 이력 저장은 시도하지 않는다")
    void couponNotFound_doesNotPersistIssue() {
        given(couponRepository.increaseIssuedCount(1L)).willReturn(0);

        assertThatThrownBy(() -> couponIssueProcessor.persistIssue(1L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 쿠폰");

        verifyNoInteractions(couponIssueRepository);
    }

    @Test
    @DisplayName("정상 발급: 재고 증가 후 (couponId, userId) 로 이력을 저장한다")
    void success_savesIssueHistory() {
        given(couponRepository.increaseIssuedCount(1L)).willReturn(1);

        couponIssueProcessor.persistIssue(1L, 100L);

        verify(couponIssueRepository).saveAndFlush(argThatMatchesIssue(1L, 100L));
    }

    @Test
    @DisplayName("이력 저장 시 유니크 제약 위반 -> 중복 발급(IllegalStateException) 으로 변환한다")
    void duplicateConstraintViolation_translatedToIllegalState() {
        given(couponRepository.increaseIssuedCount(1L)).willReturn(1);
        given(couponIssueRepository.saveAndFlush(any(CouponIssue.class)))
                .willThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> couponIssueProcessor.persistIssue(1L, 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 발급받은");
    }

    private static CouponIssue argThatMatchesIssue(Long couponId, Long userId) {
        return org.mockito.ArgumentMatchers.argThat(issue ->
                issue != null
                        && couponId.equals(issue.getCouponId())
                        && userId.equals(issue.getUserId()));
    }
}
