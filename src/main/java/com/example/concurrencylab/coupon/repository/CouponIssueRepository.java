package com.example.concurrencylab.coupon.repository;

import com.example.concurrencylab.coupon.domain.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    long countByCouponId(Long couponId);

    /** Redis 상태 재구축 시 coupon:issued:{id} 집합을 DB 이력으로 되살리는 데 쓴다. */
    @Query("select ci.userId from CouponIssue ci where ci.couponId = :couponId")
    List<Long> findUserIdsByCouponId(@Param("couponId") Long couponId);
}
