package com.example.concurrencylab.coupon.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * "누가 어떤 쿠폰을 발급받았는지" 이력 한 건.
 *
 * ─── 동시성 해결: 중복 발급 (STAR) ──────────────────────────────
 *  S 같은 유저가 동시에 100번 발급 요청 → 애플리케이션 existsBy 검사를
 *    여러 스레드가 함께 통과 → 유저당 9~10건 발급됨(전).
 *  T 유저당 정확히 1건.
 *  A (coupon_id, user_id) 복합 유니크 제약 = 최종 방어선. existsBy 는
 *    빠른 실패용 1차 방어로 남기되, 정합성 보장은 DB 제약에 맡긴다.
 *  R 유저당 9~10건 → 1건 (후). 동시 통과분은 두 번째 INSERT 가
 *    DataIntegrityViolationException 으로 거부됨.
 * ────────────────────────────────────────────────────────────────
 */
@Entity
@Table(
        name = "coupon_issue",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_coupon_issue_coupon_user",
                columnNames = {"coupon_id", "user_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long couponId;

    private Long userId;

    private LocalDateTime issuedAt;

    public CouponIssue(Long couponId, Long userId) {
        this.couponId = couponId;
        this.userId = userId;
        this.issuedAt = LocalDateTime.now();
    }
}
