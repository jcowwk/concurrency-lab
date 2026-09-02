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
 * 조회수 유실(lost update)을 재현/검증하는 테스트.
 *
 * naive 에서는 최종 viewCount 가 요청 수보다 작아져 실패했다(카운트가 조용히 사라짐).
 * Redis write-back 방식에서는 INCR 이 원자적이라 유실이 없고, flushViewCounts() 로
 * 지연 반영한 뒤의 DB view_count 가 정확히 요청 수와 같아야 한다(최종 일관성).
 *
 * 사전 조건: docker compose up -d 로 PostgreSQL, Redis 가 떠 있어야 한다.
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
