package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.CouponIssue;
import com.example.concurrencylab.coupon.repository.CouponIssueRepository;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis 판정이 끝난 뒤 DB 에 발급 사실을 기록한다(트랜잭션 1개).
 *
 * 발급 가부는 이미 Lua 스크립트가 원자적으로 결정했으므로 여기서는 락도 조건 검사도 없다.
 * 이 커밋이 실패하면 CouponIssueService 가 Redis 를 되돌린다(보상).
 * 재시도 성격이 아니라 보상이 필요하므로 트랜잭션 경계 밖(Service)에서 예외를 받는다.
 */
@Component
@RequiredArgsConstructor
public class CouponIssueProcessor {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Transactional
    public void persistIssue(Long couponId, Long userId) {
        int updated = couponRepository.increaseIssuedCount(couponId);
        if (updated == 0) {
            throw new IllegalArgumentException("존재하지 않는 쿠폰: " + couponId);
        }
        try {
            couponIssueRepository.saveAndFlush(new CouponIssue(couponId, userId));
        } catch (DataIntegrityViolationException e) {
            // Redis 발급 집합과 DB 가 어긋난 경우의 최종 방어선(유니크 제약).
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }
    }
}
