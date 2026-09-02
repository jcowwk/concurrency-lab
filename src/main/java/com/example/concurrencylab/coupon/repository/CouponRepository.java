package com.example.concurrencylab.coupon.repository;

import com.example.concurrencylab.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 발급 수량 +1. 초과 여부는 이미 Redis Lua 스크립트가 판정했으므로 조건 없이 증가시킨다.
     * DB 의 issued_count 는 Redis 상태를 뒤따라 기록하는 사본이다. 영향 행 수 0 = 쿠폰 없음.
     */
    @Modifying
    @Query("update Coupon c set c.issuedCount = c.issuedCount + 1 where c.id = :id")
    int increaseIssuedCount(@Param("id") Long id);
}
