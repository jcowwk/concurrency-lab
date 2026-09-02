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
 * naive 구현의 동시성 문제를 재현하는 테스트.
 *
 * 아래 두 테스트는 "올바른 결과"를 단언한다. naive(CouponIssueService) 에서는
 * 실패하는 것이 정상이며, 그 실패가 곧 문제의 증명이다.
 * 이후 해결책 커밋에서 같은 테스트가 통과하도록 만든다.
 *
 * 사전 조건: docker compose up -d 로 PostgreSQL 이 떠 있어야 한다.
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

        // naive 에서는 lost update 로 issuedCount 가 100 에 멈추고,
        // check-then-act race 로 issuedRows 는 100 을 넘어간다.
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

        // naive 에서는 existsBy 검사를 여러 스레드가 동시에 통과해 2건 이상 insert 된다.
        assertThat(issuedRows).isEqualTo(1);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failure()).isEqualTo(threadCount - 1);
    }
}
