package com.example.concurrencylab.article.api;

import com.example.concurrencylab.article.api.dto.CreateArticleRequest;
import com.example.concurrencylab.article.service.ArticleViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "게시글", description = "게시글 조회수 증가")
@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleViewService articleViewService;

    @Operation(summary = "게시글 생성", description = "테스트/데모용. 생성된 articleId 반환.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Long create(@Valid @RequestBody CreateArticleRequest request) {
        return articleViewService.createArticle(request.title(), request.content());
    }

    @Operation(summary = "조회수 +1",
            description = "학습용으로 증가 동작만 분리한 엔드포인트. main 은 Redis 카운터에 반영되고 약 2초 주기로 DB flush 된다.")
    @PostMapping("/{id}/views")
    @ResponseStatus(HttpStatus.OK)
    public void view(@PathVariable Long id) {
        articleViewService.increaseViewCount(id);
    }
}
