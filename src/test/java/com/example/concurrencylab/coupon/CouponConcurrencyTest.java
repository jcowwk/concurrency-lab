package com.example.concurrencylab.coupon;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.repository.CouponIssueRepository;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import com.example.concurrencylab.coupon.service.CouponIssueService;
import com.example.concurrencylab.support.ConcurrencyRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿠폰 초과/중복 발급 동시성 문제를 재현하고 해결을 검증하는 테스트.
 *
 * 전략: "올바른 결과"를 단언한다. naive 구현에서는 실패(=문제 재현),
 *      각 해결책 커밋/브랜치에서 통과(GREEN)로 바뀐다.
 *
 * 실행 결과 (콘솔의 [초과 발급] / [중복 발급] 줄):
 *   전(naive)  초과: 발급이력 300 / issuedCount 34 / 거부 0
 *              중복: 발급이력 9~10
 *   후(main)   초과: 발급이력 100 / issuedCount 100 / 거부 200
 *              중복: 발급이력 1
 *
 * 사전 조건: docker compose up -d (PostgreSQL 5432, Redis 6380)
 */
@SpringBootTest
class CouponConcurrencyTest {

    static final int TOTAL_QUANTITY = 100;

    @Autowired
    CouponIssueService couponIssueService;
    @Autowired
    CouponRepository couponRepository;
    @Autowired
    CouponIssueRepository couponIssueRepository;

    Long couponId;

    @BeforeEach
    void setUp() {
        couponIssueRepository.deleteAllInBatch();
        couponRepository.deleteAllInBatch();
        couponId = couponIssueService.createCoupon("테스트 쿠폰", TOTAL_QUANTITY);
    }

    @Test
    @DisplayName("서로 다른 유저 300명이 동시에 발급하면 정확히 100건만 발급되어야 한다")
    void 초과_발급이_발생하지_않아야_한다() throws Exception {
        int threadCount = 300;

        ConcurrencyRunner.Result result = ConcurrencyRunner.run(
                threadCount,
                userId -> couponIssueService.issue(couponId, (long) userId));

        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        long issuedRows = couponIssueRepository.countByCouponId(couponId);

        System.out.printf(
                "[초과 발급] 요청 %d / issuedCount=%d / 발급이력 row=%d / 성공=%d / 실패=%d%n",
                threadCount, coupon.getIssuedCount(), issuedRows, result.success(), result.failure());

        // naive: check-then-act race 로 issuedRows 가 300 (거부 0), lost update 로 issuedCount 는 34.
        // 해결 후: 정확히 100/100, 초과분 200 은 "소진" 예외로 실패 카운트.
        assertThat(coupon.getIssuedCount()).isEqualTo(TOTAL_QUANTITY);
        assertThat(issuedRows).isEqualTo(TOTAL_QUANTITY);
        assertThat(result.success()).isEqualTo(TOTAL_QUANTITY);
        assertThat(result.failure()).isEqualTo(threadCount - TOTAL_QUANTITY);
    }

    @Test
    @DisplayName("같은 유저가 동시에 100번 발급 요청해도 발급은 1건이어야 한다")
    void 중복_발급이_발생하지_않아야_한다() throws Exception {
        long userId = 1L;
        int threadCount = 100;

        ConcurrencyRunner.Result result = ConcurrencyRunner.run(
                threadCount,
                i -> couponIssueService.issue(couponId, userId));

        long issuedRows = couponIssueRepository.countByCouponId(couponId);

        System.out.printf(
                "[중복 발급] 요청 %d / userId=%d 발급이력 row=%d / 성공=%d / 실패=%d%n",
                threadCount, userId, issuedRows, result.success(), result.failure());

        // naive: existsBy 검사를 여러 스레드가 동시 통과 → 9~10건 insert.
        // 해결 후: 유니크 제약(+ Redis SISMEMBER)으로 1건, 나머지 99 는 "이미 발급" 예외.
        assertThat(issuedRows).isEqualTo(1);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failure()).isEqualTo(threadCount - 1);
    }
}
