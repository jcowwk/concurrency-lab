package com.example.concurrencylab.article.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 게시글 한 건. 학습 목적상 필요한 최소 필드(제목/본문)와 조회수만 들고 있다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String content;

    /** 현재까지 누적 조회수 */
    private long viewCount;

    public Article(String title, String content) {
        this.title = title;
        this.content = content;
        this.viewCount = 0L;
    }

    /**
     * 조회수를 1 증가시킨다.
     * naive 버전: 현재 값을 읽어 +1 한 값을 다시 필드에 넣는 read-modify-write.
     * 여러 트랜잭션이 같은 값을 읽고 각자 +1 하면 증가분이 유실된다(lost update).
     * flush 시점에 UPDATE article SET view_count = <절대값> 이 나가므로,
     * 동시 요청은 서로의 증가분을 덮어쓴다.
     */
    public void increaseViewCount() {
        this.viewCount += 1;
    }
}
