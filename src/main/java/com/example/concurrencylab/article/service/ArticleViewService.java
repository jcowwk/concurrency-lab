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
     * 조회수 증가 - 원자적 UPDATE.
     *
     * Article 엔티티를 읽어 필드를 고치지 않고, UPDATE article SET view_count = view_count + 1
     * 한 문장으로 처리한다. 증가 연산이 DB row 락 안에서 일어나므로 동시 요청이 서로의
     * 증가분을 덮어쓰지 않는다(lost update 제거). 영향 행 수가 0 이면 없는 게시글이다.
     *
     * 카운터 증가만 하고 그 결과값으로 분기하는 로직이 없으므로, 락도 재시도도 필요 없는
     * 이 방식이 조회수에는 가장 잘 맞는다.
     */
    @Transactional
    public void increaseViewCount(Long articleId) {
        int updated = articleRepository.increaseViewCount(articleId);
        if (updated == 0) {
            throw new IllegalArgumentException("존재하지 않는 게시글: " + articleId);
        }
    }
}
