package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.domain.CouponIssue;
import com.example.concurrencylab.coupon.repository.CouponIssueRepository;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
     * 쿠폰 발급 - 조건부 원자적 UPDATE.
     *
     *   1) 쿠폰 존재 확인 (existsById)
     *   2) 중복 발급 검사 (existsBy)     - 1차 방어
     *   3) UPDATE ... SET issued_count = issued_count + 1 WHERE issued_count < total_quantity
     *      영향 행 수 0 이면 소진
     *   4) 발급 이력 저장 (유니크 제약이 중복의 최종 방어선)
     *
     * 초과 발급 방지를 위해 Coupon 엔티티를 읽어 필드를 고치지 않는다. 증가와 상한 검사가
     * DB 한 문장 안에서 원자적으로 일어나므로 락을 오래 잡지 않고 재시도도 필요 없다.
     */
    @Transactional
    public void issue(Long couponId, Long userId) {
        if (!couponRepository.existsById(couponId)) {
            throw new IllegalArgumentException("존재하지 않는 쿠폰: " + couponId);
        }

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }

        int updated = couponRepository.increaseIssuedCountIfAvailable(couponId);
        if (updated == 0) {
            throw new IllegalStateException("쿠폰이 모두 소진되었습니다. couponId=" + couponId);
        }

        try {
            couponIssueRepository.saveAndFlush(new CouponIssue(couponId, userId));
        } catch (DataIntegrityViolationException e) {
            // existsBy 를 동시에 통과한 요청이 여기서 걸린다. 트랜잭션이 롤백되며 3)의 증가도 취소된다.
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }
    }
}
