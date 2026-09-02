package com.example.concurrencylab.article.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateArticleRequest(
        @NotBlank String title,
        @NotBlank String content
) {
}
