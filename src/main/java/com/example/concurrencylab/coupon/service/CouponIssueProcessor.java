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
 * ─── 왜 Service 와 분리했나 (STAR) ───────────────────────────────
 *  S 트랜잭션은 메서드가 리턴한 뒤 커밋된다. 커밋 시점에 나는 예외(제약 위반 등)는
 *    메서드 안 try/catch 로 못 잡고, 잡더라도 롤백은 이미 예약된 상태다.
 *  T Redis 보상은 DB 트랜잭션이 확실히 끝난 뒤에 실행돼야 한다.
 *  A DB 기록만 별도 @Transactional 빈으로 분리 → 이 메서드가 리턴/예외를 던지는
 *    순간 트랜잭션이 정리되고, 호출자(CouponIssueService)가 그 결과를 받아 보상한다.
 *  R "락 획득/판정 → (DB 커밋 완료) → 보상" 순서가 코드 구조로 강제된다.
 * ────────────────────────────────────────────────────────────────
 *
 * 발급 가부는 이미 Lua 스크립트가 결정했으므로 여기서는 락도 조건 검사도 없다.
 * saveAndFlush 로 INSERT 를 지금 실행해야 유니크 제약 위반을 여기서 잡을 수 있다
 * (save 만 하면 커밋 시점 = 이 메서드 밖에서 터진다).
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
