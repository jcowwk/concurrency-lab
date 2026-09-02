package com.example.concurrencylab.coupon.repository;

import com.example.concurrencylab.coupon.domain.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 발급 시 사용하는 조회. SELECT ... FOR UPDATE 로 해당 쿠폰 row 에 배타 락을 건다.
     * 같은 쿠폰에 대한 동시 발급 요청은 이 지점에서 직렬화되므로,
     * 이어지는 read-modify-write (issuedCount += 1) 의 lost update 가 사라진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);
}
