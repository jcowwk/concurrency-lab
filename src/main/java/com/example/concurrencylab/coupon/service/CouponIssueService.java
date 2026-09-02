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
     * 쿠폰 발급 - Redis 분산락(Redisson).
     *
     * 쿠폰 단위 락("coupon:lock:{id}")으로 임계구역을 보호한다. JVM synchronized 와 달리
     * 애플리케이션 인스턴스가 여러 대여도 Redis 를 통해 상호배제가 성립한다.
     *
     * 락 경계는 트랜잭션 경계 밖에 둔다. 실제 발급은 CouponIssueProcessor 가 트랜잭션으로
     * 수행하고, 그 커밋이 끝난 뒤에야 finally 에서 unlock 한다. issue() 에 @Transactional 을
     * 붙이면 커밋이 unlock 이후에 일어나 그 틈에 다른 노드가 stale read 로 끼어든다.
     *
     * leaseTime 을 명시하지 않아 Redisson watchdog 이 락을 자동 연장한다. 다만 프로세스가
     * STW(GC)로 얼어붙으면 watchdog 도 멈춰 락이 만료되고, 두 노드가 동시에 임계구역에
     * 들어갈 수 있다(분산락의 근본 한계). 최종 방어선으로 DB 유니크 제약은 유지한다.
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
