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
     * ─── 초과 발급 해결안 ② 낙관적 락 + 재시도 (STAR) ────────────────────────
     *  S 300명이 같은 쿠폰 row 를 동시에 노림.
     *  T 300 동시요청 → 정확히 100건.
     *  A Coupon 에 @Version. 락을 미리 잡지 않고, 커밋 시 UPDATE ... WHERE version=?
     *    로 "내가 읽은 뒤 아무도 안 바꿨나"만 검사(CAS). 어긋나면
     *    ObjectOptimisticLockingFailureException → 여기서 잡아 새 트랜잭션으로 재시도.
     *    재시도는 트랜잭션 경계 밖이어야 하므로 이 메서드엔 @Transactional 없음
     *    (충돌 예외는 커밋 시점 = issueOnce() 밖에서 난다).
     *  R 발급 300→100. 그러나 300건 처리 1.16~1.25s — 원자 UPDATE(0.5s대)보다 2~3배 느림.
     *    선착순 쿠폰 = 같은 row 에 수백 요청 = 충돌·재시도 폭증.
     *  결론  "충돌이 예외적일 때 쓰는 기법을 충돌이 일상인 곳에 쓰면 안 된다."
     *        경합 낮은 자원(유저별 데이터 등) 수정에는 여전히 좋은 선택.
     * ────────────────────────────────────────────────────────────────────────
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
