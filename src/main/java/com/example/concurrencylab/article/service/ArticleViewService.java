package com.example.concurrencylab.article.service;

import com.example.concurrencylab.article.domain.Article;
import com.example.concurrencylab.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ArticleViewService {

    private final ArticleRepository articleRepository;

    @Transactional
    public Long createArticle(String title, String content) {
        return articleRepository.save(new Article(title, content)).getId();
    }

    /**
     * 조회수 증가 - naive 버전 (동시성 미고려).
     *
     *   1) 게시글 조회        (read)
     *   2) viewCount + 1      (modify)
     *   3) flush 시 UPDATE    (write)
     *
     * read 로 얻은 값을 기준으로 modify 하고 write 하기 때문에, 두 트랜잭션이
     * 같은 viewCount 를 읽으면 둘 다 같은 값을 써서 한쪽 증가분이 사라진다.
     * PostgreSQL 기본 격리 수준(READ COMMITTED)에서는 서로의 미커밋 증가가
     * 보이지 않으므로 write-write 충돌로도 감지되지 않는다.
     *
     * 단일 스레드로 N 번 호출하면 항상 viewCount == N 이라 문제가 드러나지 않는다.
     */
    @Transactional
    public void increaseViewCount(Long articleId) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시글: " + articleId));

        // article 은 영속 상태이므로 필드 변경만으로 flush 시점에 UPDATE 가 나간다(더티 체킹).
        article.increaseViewCount();
    }
}
