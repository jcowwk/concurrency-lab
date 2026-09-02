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
 * 발급 1회 = 트랜잭션 1개.
 *
 * 락을 미리 잡지 않고 findById 로 읽은 뒤 issuedCount 를 +1 한다.
 * 커밋 시 UPDATE coupon SET ..., version = version + 1 WHERE id = ? AND version = ?
 * 가 나가고, 그 사이 다른 트랜잭션이 먼저 커밋해 version 이 바뀌었으면
 * ObjectOptimisticLockingFailureException 이 발생한다(update 결과 0건).
 *
 * 이 예외는 트랜잭션 커밋 시점에 터지므로 재시도는 이 트랜잭션 밖
 * (CouponIssueService)에서 새 트랜잭션으로 수행해야 한다.
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
