package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.domain.CouponIssue;
import com.example.concurrencylab.coupon.repository.CouponIssueRepository;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급 1회 = 트랜잭션 1개. 상호배제는 바깥의 분산락이 이미 보장하므로
 * 여기서는 락 없이 read-modify-write 를 해도 안전하다.
 *
 * CouponIssueService(락 획득) 와 분리한 이유: 락 해제는 반드시 이 트랜잭션이
 * 커밋된 뒤에 일어나야 한다. issue() 자체에 @Transactional 을 붙이면 커밋이
 * 메서드 리턴 이후 = unlock 이후에 일어나 그 사이 다른 노드가 끼어들 수 있다.
 */
@Component
@RequiredArgsConstructor
public class CouponIssueProcessor {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Transactional
    public void issueOnce(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰: " + couponId));

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }

        if (coupon.isSoldOut()) {
            throw new IllegalStateException("쿠폰이 모두 소진되었습니다. couponId=" + couponId);
        }

        coupon.increaseIssuedCount();
        try {
            couponIssueRepository.saveAndFlush(new CouponIssue(couponId, userId));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }
    }
}
