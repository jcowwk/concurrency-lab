package com.example.concurrencylab.article.repository;

import com.example.concurrencylab.article.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    /**
     * 조회수를 delta 만큼 더한다. Redis 에 모인 지연 증가분을 주기적으로 DB 에 반영할 때 쓴다.
     * 절대값이 아니라 증분(+= delta)이므로 flush 가 여러 번/부분적으로 일어나도 합계가 맞다.
     * 메서드마다 독립 트랜잭션 - 한 행이 실패해도 다른 행 반영은 유지된다. 영향 행 수 0 = 게시글 없음.
     */
    @Transactional
    @Modifying
    @Query("update Article a set a.viewCount = a.viewCount + :delta where a.id = :id")
    int increaseViewCountBy(@Param("id") Long id, @Param("delta") long delta);
}
