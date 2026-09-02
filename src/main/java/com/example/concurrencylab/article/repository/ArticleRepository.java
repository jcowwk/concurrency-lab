package com.example.concurrencylab.article.repository;

import com.example.concurrencylab.article.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    /**
     * 조회수를 1 증가시키는 원자적 UPDATE.
     *
     * 애플리케이션이 현재 값을 읽어 +1 하지 않고 DB 한 문장으로 처리한다.
     * UPDATE 실행 중 해당 row 에 걸리는 락 덕분에 동시 요청이 서로의 증가분을
     * 덮어쓰지 않는다(lost update 제거). 반환값은 영향받은 행 수.
     */
    @Modifying
    @Query("update Article a set a.viewCount = a.viewCount + 1 where a.id = :id")
    int increaseViewCount(@Param("id") Long id);
}
