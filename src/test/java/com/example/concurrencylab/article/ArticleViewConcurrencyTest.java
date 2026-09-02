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
 * 조회수 유실(lost update)을 재현하는 테스트.
 *
 * naive(ArticleViewService) 에서는 최종 viewCount 가 요청 수보다 작아져 실패한다.
 * 예외는 발생하지 않고 카운트만 조용히 사라지는 것이 이 문제의 특징이다.
 *
 * 사전 조건: docker compose up -d 로 PostgreSQL 이 떠 있어야 한다.
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

        Article article = articleRepository.findById(articleId).orElseThrow();

        System.out.printf(
                "[조회수 유실] 요청 %d / viewCount=%d / 유실=%d / 성공=%d / 실패=%d%n",
                threadCount, article.getViewCount(), threadCount - article.getViewCount(),
                result.success(), result.failure());

        assertThat(result.failure()).isZero();
        assertThat(article.getViewCount()).isEqualTo(1000L);
    }
}
