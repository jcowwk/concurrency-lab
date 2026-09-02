package com.example.concurrencylab.coupon.repository;

import com.example.concurrencylab.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * ─── 초과 발급 해결안 ③ 조건부 원자적 UPDATE (STAR) ──────────────────────
     *  S 애플리케이션이 재고를 읽어(read) +1 해서(modify) 쓰는(write) 사이에 경합.
     *  T 300 동시요청 → 정확히 100건.
     *  A 읽기-수정-쓰기를 애플리케이션이 하지 않고 DB 한 문장으로 넘긴다.
     *    UPDATE 실행 중 걸리는 행 락으로 lost update 가 없고,
     *    WHERE issued_count < total_quantity 조건이 초과를 막는다. 영향 행 수 0 = 소진.
     *  R 발급 300→100. 300건 처리 0.49~0.58s — 비교군 중 DB 단독 최고.
     *    락 구간이 UPDATE 한 문장뿐, 재시도 없음, 엔티티 로딩/더티체킹도 생략.
     *  주의  @Modifying 쿼리는 영속성 컨텍스트를 우회 → 같은 트랜잭션에서 그 엔티티를
     *        다시 읽어 쓰면 값이 안 맞을 수 있다(여기선 이후 Coupon 을 안 읽으니 무관).
     *  적합  "재고 있으면 -1" 처럼 로직이 SQL 한 줄로 표현되는 단순 카운터/재고 → 1순위.
     * ────────────────────────────────────────────────────────────────────────
     */
    @Modifying
    @Query("update Coupon c set c.issuedCount = c.issuedCount + 1 " +
            "where c.id = :id and c.issuedCount < c.totalQuantity")
    int increaseIssuedCountIfAvailable(@Param("id") Long id);
}
