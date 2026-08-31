package com.example.concurrencylab.coupon.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 선착순 한정수량 쿠폰 캠페인.
 * 하나의 로우가 "쿠폰 종류 1개"를 의미하며, 총 수량과 현재 발급 수량을 들고 있다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** 발급 가능한 총 수량 (예: 100) */
    private int totalQuantity;

    /** 현재까지 발급된 수량 */
    private int issuedCount;

    public Coupon(String name, int totalQuantity) {
        this.name = name;
        this.totalQuantity = totalQuantity;
        this.issuedCount = 0;
    }

    public boolean isSoldOut() {
        return issuedCount >= totalQuantity;
    }

    /**
     * 발급 수량을 1 증가시킨다.
     * naive 버전: 현재 값을 읽어 +1 한 값을 다시 필드에 넣는 read-modify-write.
     * 여러 트랜잭션이 같은 값을 읽고 각자 +1 하면 증가분이 유실된다(lost update).
     */
    public void increaseIssuedCount() {
        this.issuedCount += 1;
    }
}
