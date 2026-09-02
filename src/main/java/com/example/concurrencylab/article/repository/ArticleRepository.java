package com.example.concurrencylab.article.repository;

import com.example.concurrencylab.article.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    /**
     * ─── 조회수 유실 해결안 ① DB 원자적 UPDATE (STAR) ───────────────────────
     *  S "조회수 읽기 → +1 → 저장"(read-modify-write). 동시 조회가 같은 값을 읽고
     *    같은 절대값을 써서 증가분이 조용히 사라진다. 1000 동시조회 → ~110 (89% 유실).
     *  T 1000 동시조회 → 정확히 1000.
     *  A 애플리케이션이 현재 값을 읽지 않고 DB 한 문장으로 처리.
     *    UPDATE 중 걸리는 행 락으로 동시 요청이 서로를 못 덮어쓴다.
     *  R 유실 89% → 0%. 1000건 처리 1.4~1.6s (naive 와 비슷 - 둘 다 조회당 DB 쓰기 1건).
     *  한계  조회 트래픽에 비례해 DB 쓰기가 늘어난다. 조회량이 많으면 main 의
     *        Redis write-back(조회당 INCR, flush당 DB 쓰기 1회)으로 간다.
     *        대신 이 방식은 조회수가 즉시 정확(최종 일관성 아님).
     * ────────────────────────────────────────────────────────────────────────
     */
    @Modifying
    @Query("update Article a set a.viewCount = a.viewCount + 1 where a.id = :id")
    int increaseViewCount(@Param("id") Long id);
}
