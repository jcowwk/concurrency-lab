package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    /** version 충돌로 인한 재시도 한도. 초과하면 발급 실패로 처리한다. */
    private static final int MAX_ATTEMPTS = 100;

    private final CouponRepository couponRepository;
    private final CouponIssueProcessor couponIssueProcessor;

    @Transactional
    public Long createCoupon(String name, int totalQuantity) {
        return couponRepository.save(new Coupon(name, totalQuantity)).getId();
    }

    /**
     * 쿠폰 발급 - 낙관적 락 + 재시도.
     *
     * 실제 발급은 {@link CouponIssueProcessor#issueOnce} 가 트랜잭션 1개로 수행한다.
     * 커밋 시 version 이 어긋나면 ObjectOptimisticLockingFailureException 이 나고,
     * 여기서 잡아 새 트랜잭션으로 재시도한다. 재시도는 반드시 트랜잭션 경계 밖에서
     * 이뤄져야 하므로 이 메서드에는 @Transactional 을 붙이지 않는다.
     *
     * 경합이 심하면(같은 쿠폰에 수백 요청) 재시도가 누적되어 처리량이 급락한다.
     * 이것이 낙관적 락의 트레이드오프다 - 충돌이 드물 때만 유리하다.
     */
    public void issue(Long couponId, Long userId) {
        for (int attempt = 1; ; attempt++) {
            try {
                couponIssueProcessor.issueOnce(couponId, userId);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "발급 요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");
                }
                backoff(attempt);
            }
        }
    }

    /** 충돌한 요청들이 같은 순간에 재시도해 다시 충돌하는 것을 줄이기 위한 지터 백오프. */
    private void backoff(int attempt) {
        try {
            long sleepMs = Math.min(attempt, 10) + ThreadLocalRandom.current().nextLong(5);
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("발급 처리가 중단되었습니다.", ie);
        }
    }
}
