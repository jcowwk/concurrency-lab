package com.example.concurrencylab.article.repository;

import com.example.concurrencylab.article.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleRepository extends JpaRepository<Article, Long> {
}
