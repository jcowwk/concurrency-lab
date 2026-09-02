package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private static final long LOCK_WAIT_SECONDS = 10;

    private final CouponRepository couponRepository;
    private final CouponIssueProcessor couponIssueProcessor;
    private final RedissonClient redissonClient;

    @Transactional
    public Long createCoupon(String name, int totalQuantity) {
        return couponRepository.save(new Coupon(name, totalQuantity)).getId();
    }

    /**
     * ─── 초과 발급 해결안 ④ Redis 분산락 (Redisson) (STAR) ──────────────────
     *  S 앱 인스턴스가 여러 대면 JVM synchronized 로는 상호배제가 안 된다(각 JVM 별개).
     *  T 여러 노드에 걸쳐 같은 쿠폰 발급을 직렬화. 300 동시요청 → 정확히 100건.
     *  A 쿠폰 단위 락 RLock("coupon:lock:{id}") 으로 임계구역 보호. 락은 Redis 에 있고
     *    데이터는 여전히 DB. 실제 발급은 CouponIssueProcessor 가 트랜잭션으로 수행하고,
     *    그 커밋이 끝난 뒤 finally 에서 unlock 한다.
     *  R 발급 300→100. 300건 처리 1.6s대 — 직렬화 + 매 요청 Redis 왕복이 더해짐.
     *  핵심 함정  락 해제는 반드시 트랜잭션 커밋 뒤. issue() 에 @Transactional 을 붙이면
     *            커밋이 unlock 이후로 밀려, 그 틈에 다른 노드가 커밋 전 상태를 읽는다
     *            (stale read). 그래서 발급 로직을 별도 빈으로 분리.
     *  한계  leaseTime 만료 + STW(GC) 가 겹치면 두 노드가 동시 진입 가능(분산락의 근본 한계).
     *        → DB 유니크 제약을 최종 방어선으로 유지. Redis 다운 = 발급 불가(가용성 종속).
     * ────────────────────────────────────────────────────────────────────────
     */
    public void issue(Long couponId, Long userId) {
        RLock lock = redissonClient.getLock("coupon:lock:" + couponId);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("발급 처리가 중단되었습니다.", e);
        }
        if (!acquired) {
            throw new IllegalStateException("발급 요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");
        }

        try {
            couponIssueProcessor.issueOnce(couponId, userId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
