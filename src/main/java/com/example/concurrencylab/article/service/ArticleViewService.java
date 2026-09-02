package com.example.concurrencylab.article.service;

import com.example.concurrencylab.article.domain.Article;
import com.example.concurrencylab.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ArticleViewService {

    private static final String DIRTY_KEY = "article:view:dirty";
    private static final String FLUSH_LOCK_KEY = "article:view:flush:lock";

    private final ArticleRepository articleRepository;
    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;

    private static String deltaKey(long articleId) {
        return "article:view:delta:" + articleId;
    }

    @Transactional
    public Long createArticle(String title, String content) {
        Long id = articleRepository.save(new Article(title, content)).getId();
        // 같은 id 로 재생성해도(테스트 등) 지연 증가분이 남지 않도록 초기화
        redis.delete(deltaKey(id));
        redis.opsForSet().remove(DIRTY_KEY, String.valueOf(id));
        return id;
    }

    /**
     * 조회수 증가 - Redis 카운터(write-back).
     *
     * 매 조회마다 DB 를 쓰지 않고 Redis 에 INCR 만 한다. INCR 은 원자적이라 동시 조회에도
     * 증가분이 유실되지 않는다(재현 테스트의 lost update 해결). DB 의 view_count 는
     * flushViewCounts() 가 주기적으로 지연 반영한다 = 최종 일관성.
     *
     * 핫패스에서 게시글 존재 여부를 확인하지 않는다(DB 왕복 제거가 목적). 없는 게시글의
     * 증가분은 flush 시 UPDATE 영향 행 수 0 으로 조용히 폐기된다.
     */
    public void increaseViewCount(Long articleId) {
        redis.opsForValue().increment(deltaKey(articleId));
        redis.opsForSet().add(DIRTY_KEY, String.valueOf(articleId));
    }

    /**
     * Redis 에 쌓인 지연 증가분을 DB 에 반영한다.
     *
     * 다중 인스턴스에서 모두가 동시에 flush 하지 않도록 분산락으로 한 번에 한 인스턴스만
     * 수행한다(획득 실패 시 그냥 건너뜀 - 다음 주기/다른 인스턴스가 처리).
     *
     * id 를 dirty 집합에서 먼저 제거한 뒤 GETDEL 로 delta 를 가져온다. 그 사이 새 조회가
     * 들어오면 delta 키가 다시 생기고 dirty 에 다시 등록되므로 증가분을 잃지 않는다.
     */
    public int flushViewCounts() {
        RLock lock = redissonClient.getLock(FLUSH_LOCK_KEY);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        if (!acquired) {
            return 0;
        }

        try {
            Set<String> ids = redis.opsForSet().members(DIRTY_KEY);
            if (ids == null || ids.isEmpty()) {
                return 0;
            }
            int flushed = 0;
            for (String idStr : ids) {
                redis.opsForSet().remove(DIRTY_KEY, idStr);
                String taken = redis.opsForValue().getAndDelete(deltaKey(Long.parseLong(idStr)));
                long delta = (taken == null) ? 0L : Long.parseLong(taken);
                if (delta == 0L) {
                    continue;
                }
                int updated = articleRepository.increaseViewCountBy(Long.parseLong(idStr), delta);
                if (updated > 0) {
                    flushed++;
                }
            }
            return flushed;
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedDelayString = "${app.view-count.flush-interval-ms:2000}")
    public void scheduledFlush() {
        flushViewCounts();
    }
}
