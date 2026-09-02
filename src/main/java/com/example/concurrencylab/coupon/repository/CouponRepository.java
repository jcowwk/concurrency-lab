package com.example.concurrencylab.coupon.repository;

import com.example.concurrencylab.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    // 이 브랜치는 상호배제를 Redis 분산락으로 처리한다. DB 락(FOR UPDATE)은 쓰지 않는다.
}
