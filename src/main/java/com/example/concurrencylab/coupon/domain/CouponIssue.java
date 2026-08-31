package com.example.concurrencylab.coupon.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * "누가 어떤 쿠폰을 발급받았는지" 이력 한 건.
 *
 * naive 버전에서는 (couponId, userId) 에 대한 유니크 제약을 일부러 걸지 않는다.
 * 중복 발급 방어를 애플리케이션 코드의 existsBy... 검사에만 의존하게 만들어,
 * 동시 요청에서 같은 유저가 여러 건 들어오는 상황을 재현하기 위함이다.
 */
@Entity
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
