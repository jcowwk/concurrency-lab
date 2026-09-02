package com.example.concurrencylab.coupon.repository;

import com.example.concurrencylab.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 잔여 수량이 있을 때만 발급 수량을 1 증가시키는 조건부 원자적 UPDATE.
     *
     * 읽기-수정-쓰기를 애플리케이션이 하지 않고 DB 한 문장으로 처리한다.
     * UPDATE 실행 중 해당 row 에 걸리는 락 덕분에 lost update 가 없고,
     * WHERE issued_count < total_quantity 조건이 초과 발급을 막는다.
     * 반환값(영향받은 행 수)이 0 이면 이미 소진된 것이다.
     */
    @Modifying
    @Query("update Coupon c set c.issuedCount = c.issuedCount + 1 " +
            "where c.id = :id and c.issuedCount < c.totalQuantity")
    int increaseIssuedCountIfAvailable(@Param("id") Long id);
}
