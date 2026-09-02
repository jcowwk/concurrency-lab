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
 * (couponId, userId) 복합 유니크 제약이 중복 발급의 최종 방어선이다.
 * 애플리케이션의 existsBy... 사전 검사를 동시 요청이 함께 통과하더라도,
 * 두 번째 INSERT 는 이 제약에 걸려 DataIntegrityViolationException 으로 거부된다.
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
