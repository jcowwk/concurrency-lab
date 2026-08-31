package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.domain.CouponIssue;
import com.example.concurrencylab.coupon.repository.CouponIssueRepository;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Transactional
    public Long createCoupon(String name, int totalQuantity) {
        return couponRepository.save(new Coupon(name, totalQuantity)).getId();
    }

    /**
     * 쿠폰 발급 - naive 버전 (동시성 미고려).
     *
     * 정상 흐름(happy path)만 고려한 구조:
     *   1) 쿠폰 조회
     *   2) 중복 발급 검사        (check)
     *   3) 잔여 수량 검사        (check)
     *   4) 발급 수량 +1, 발급 이력 저장  (act)
     *
     * check(2,3) 와 act(4) 는 원자적이지 않다. 두 검사를 통과한 뒤 act 하기까지의
     * 사이에 다른 트랜잭션이 끼어들어 같은 검사를 통과해 버릴 수 있다.
     * 단일 스레드로 호출하면 항상 정상 동작하므로 버그가 눈에 띄지 않는다.
     */
    @Transactional
    public void issue(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰: " + couponId));

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }

        if (coupon.isSoldOut()) {
            throw new IllegalStateException("쿠폰이 모두 소진되었습니다. couponId=" + couponId);
        }

        // coupon 은 영속 상태이므로 필드 변경만으로 flush 시점에 UPDATE 가 나간다(더티 체킹).
        coupon.increaseIssuedCount();
        couponIssueRepository.save(new CouponIssue(couponId, userId));
    }
}
