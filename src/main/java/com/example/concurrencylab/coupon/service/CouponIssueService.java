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
     * 쿠폰 발급.
     *
     *   1) 쿠폰 조회
     *   2) 중복 발급 검사 (existsBy)  - 1차 방어. 정상 케이스에서 불필요한 INSERT/예외를 피한다.
     *   3) 잔여 수량 검사
     *   4) 발급 수량 +1, 발급 이력 저장
     *
     * 중복 발급의 최종 보장은 (couponId, userId) DB 유니크 제약이 담당한다.
     * 동시 요청이 2) existsBy 를 함께 통과하더라도, 두 번째 INSERT 는 제약에 걸려
     * DataIntegrityViolationException 이 되고, 이를 잡아 IllegalStateException 으로 변환한다.
     * saveAndFlush 로 INSERT 를 이 자리에서 실행해야 제약 위반을 여기서 포착할 수 있다
     * (save 만 하면 트랜잭션 커밋 시점에 INSERT 가 나가 catch 밖에서 터진다).
     *
     * 초과 발급은 findByIdForUpdate (SELECT ... FOR UPDATE) 로 막는다. 같은 쿠폰에 대한
     * 동시 발급이 쿠폰 row 배타 락으로 직렬화되므로, 3) 잔여 수량 검사와 4) issuedCount += 1
     * 이 한 트랜잭션 안에서 원자적으로 수행된다.
     */
    @Transactional
    public void issue(Long couponId, Long userId) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰: " + couponId));

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }

        if (coupon.isSoldOut()) {
            throw new IllegalStateException("쿠폰이 모두 소진되었습니다. couponId=" + couponId);
        }

        // coupon 은 영속 상태이므로 필드 변경만으로 flush 시점에 UPDATE 가 나간다(더티 체킹).
        coupon.increaseIssuedCount();
        try {
            couponIssueRepository.saveAndFlush(new CouponIssue(couponId, userId));
        } catch (DataIntegrityViolationException e) {
            // existsBy 를 동시에 통과한 요청이 여기서 걸린다.
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }
    }
}
