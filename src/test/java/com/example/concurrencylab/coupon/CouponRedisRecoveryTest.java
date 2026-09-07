package com.example.concurrencylab.coupon;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.repository.CouponIssueRepository;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import com.example.concurrencylab.coupon.service.CouponIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 장애 시나리오 — "Redis 데이터 유실" 을 재현하고 복구를 검증한다.
 *
 * FLUSHDB 로 Redis 상태(coupon:stock / coupon:issued)를 통째로 날린 뒤:
 *   1) 발급 시도 → 전부 거부(fail-safe). 초과 발급도 유령 발급도 안 생긴다.
 *   2) rebuildRedisState() 로 DB 이력에서 Redis 재구축.
 *   3) 발급 재개 시 이미 받은 유저는 중복 거부, 남은 재고까지만 정확히 발급.
 *
 * (연결 자체가 끊기는 시나리오 = 503 응답은 scripts/redis-failure-drill.sh 로 확인)
 *
 * 사전 조건: docker compose up -d (PostgreSQL 5432, Redis 6380)
 */
@SpringBootTest
class CouponRedisRecoveryTest {

    static final int STOCK = 5;

    @Autowired
    CouponIssueService couponIssueService;
    @Autowired
    CouponRepository couponRepository;
    @Autowired
    CouponIssueRepository couponIssueRepository;
    @Autowired
    StringRedisTemplate redis;

    Long couponId;

    private void flushRedis() {
        RedisConnectionFactory cf = Objects.requireNonNull(redis.getConnectionFactory());
        cf.getConnection().serverCommands().flushDb();
    }

    @BeforeEach
    void setUp() {
        couponIssueRepository.deleteAllInBatch();
        couponRepository.deleteAllInBatch();
        flushRedis();
        couponId = couponIssueService.createCoupon("복구 테스트", STOCK);
    }

    @Test
    @DisplayName("Redis 데이터 유실 → 발급 거부(fail-safe), rebuild 후 정상 복구 · 중복/초과 없음")
    void redis_flush_then_rebuild() {
        // 정상 발급 2건
        couponIssueService.issue(couponId, 1L);
        couponIssueService.issue(couponId, 2L);
        assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(2);

        // ── Redis 전체 유실 (재시작+AOF 갭 / eviction / 빈 복제본 failover 시뮬레이션) ──
        flushRedis();

        // 재고 키가 사라졌으므로 Lua 는 -2(쿠폰 없음) → 발급 전부 거부. DB 는 그대로 2건.
        assertThatThrownBy(() -> couponIssueService.issue(couponId, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(2);

        // ── DB 이력으로 Redis 재구축 ──
        couponIssueService.rebuildRedisState(couponId);
        assertThat(redis.opsForValue().get("coupon:stock:" + couponId)).isEqualTo("3"); // 5 - 2
        assertThat(redis.opsForSet().size("coupon:issued:" + couponId)).isEqualTo(2L);

        // 복구 후: 이미 받은 유저(1) 는 중복 거부
        assertThatThrownBy(() -> couponIssueService.issue(couponId, 1L))
                .isInstanceOf(IllegalStateException.class);

        // 새 유저는 남은 3장까지만 발급, 그 다음은 소진
        couponIssueService.issue(couponId, 3L);
        couponIssueService.issue(couponId, 4L);
        couponIssueService.issue(couponId, 5L);
        assertThatThrownBy(() -> couponIssueService.issue(couponId, 6L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(couponIssueRepository.countByCouponId(couponId)).isEqualTo(STOCK);
        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(coupon.getIssuedCount()).isEqualTo(STOCK); // 이력 수 == 카운터

        System.out.printf("[Redis 복구] 유실 전 2건 → FLUSHDB → 발급 거부 → rebuild(재고 3, 집합 2) → 최종 %d건, 초과/중복 0%n",
                couponIssueRepository.countByCouponId(couponId));
    }
}
