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
     * [naive 참고용 - main(Redis write-back)에서는 호출되지 않음]
     *
     * 현재 값을 읽어 +1 한 값을 다시 필드에 넣는 read-modify-write.
     * 더티체킹으로 flush 시점에 UPDATE article SET view_count = <절대값> 이 나가는데,
     * 동시 트랜잭션이 같은 값을 읽고 같은 절대값을 써서 증가분이 유실된다(lost update).
     * → 1000 동시조회 시 최종 ~110 (89% 유실). 이 문제를 재현하는 게 시작점이었다.
     *
     * 해결 흐름:  이 방식(naive) → DB 원자 UPDATE(solution/article-db-atomic)
     *            → Redis INCR write-back(main, ArticleViewService.increaseViewCount)
     */
    public void increaseViewCount() {
        this.viewCount += 1;
    }
}
