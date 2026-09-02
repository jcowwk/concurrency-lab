package com.example.concurrencylab.article;

import com.example.concurrencylab.article.domain.Article;
import com.example.concurrencylab.article.repository.ArticleRepository;
import com.example.concurrencylab.article.service.ArticleViewService;
import com.example.concurrencylab.support.ConcurrencyRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게시글 조회수 유실(lost update)을 재현하고 해결을 검증하는 테스트.
 *
 * 실행 결과 (콘솔의 [조회수 유실] 줄):
 *   전(naive)          viewCount ~110 / 유실 ~890  (1000 요청, 예외 0)
 *   후(main, write-back) viewCount 1000 / 유실 0
 *
 * main 은 최종 일관성이라, 동시 조회 뒤 flushViewCounts() 로 델타를 DB 에 내린 다음
 * 값을 확인한다. (INCR 자체는 원자적이라 Redis 쪽 카운트는 항상 정확하다.)
 *
 * 사전 조건: docker compose up -d (PostgreSQL 5432, Redis 6380)
 */
@SpringBootTest
class ArticleViewConcurrencyTest {

    @Autowired
    ArticleViewService articleViewService;
    @Autowired
    ArticleRepository articleRepository;

    Long articleId;

    @BeforeEach
    void setUp() {
        articleRepository.deleteAllInBatch();
        articleId = articleViewService.createArticle("제목", "본문");
    }

    @Test
    @DisplayName("동시에 1000번 조회하면 조회수가 정확히 1000이어야 한다")
    void 조회수가_유실되지_않아야_한다() throws Exception {
        int threadCount = 1000;

        ConcurrencyRunner.Result result = ConcurrencyRunner.run(
                threadCount,
                i -> articleViewService.increaseViewCount(articleId));

        // 지연 반영분을 DB 로 내린다(스케줄러가 이미 일부 반영했을 수 있으나 증분이라 합계는 맞다).
        articleViewService.flushViewCounts();

        Article article = articleRepository.findById(articleId).orElseThrow();

        System.out.printf(
                "[조회수 유실] 요청 %d / viewCount=%d / 유실=%d / 성공=%d / 실패=%d%n",
                threadCount, article.getViewCount(), threadCount - article.getViewCount(),
                result.success(), result.failure());

        assertThat(result.failure()).isZero();
        assertThat(article.getViewCount()).isEqualTo(1000L);
    }
}
