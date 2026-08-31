package com.example.concurrencylab.coupon.repository;

import com.example.concurrencylab.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
