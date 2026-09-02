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
     * ─── 초과 발급 해결안 ① 비관적 락 (STAR) ─────────────────────────────
     *  S 300명이 같은 쿠폰의 재고를 동시에 읽고 각자 +1 → 초과 발급 + 카운터 유실.
     *  T 300 동시요청 → 정확히 100건, 카운터도 100.
     *  A findById 대신 이 메서드로 조회. @Lock(PESSIMISTIC_WRITE) → SELECT ... FOR UPDATE
     *    로 쿠폰 row 에 배타 락. 같은 쿠폰 발급이 이 지점에서 완전히 직렬화되어,
     *    이어지는 재고 검사 + issuedCount += 1 이 한 트랜잭션 안에서 원자적으로 수행된다.
     *  R 발급 300→100, 카운터 34→100. 300건 처리 0.72~0.89s.
     *    (원자 UPDATE 0.5s대보다 느림 - 락 구간에 INSERT+인덱스 갱신까지 포함되어 커넥션 오래 점유)
     *  적합  로직이 SQL 한 줄로 안 될 만큼 복잡하고, 단일 DB 로 감당되는 규모.
     * ────────────────────────────────────────────────────────────────────
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);
}
