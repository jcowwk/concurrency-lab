package com.example.concurrencylab.article.service;

import com.example.concurrencylab.article.domain.Article;
import com.example.concurrencylab.article.repository.ArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ArticleViewService 단위 테스트 (Mockito). DB/Redis 없이 write-back 로직의 분기만 검증한다.
 * 동시 조회 시 실제 유실 0 인지는 ArticleViewConcurrencyTest(통합) 로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ArticleViewServiceTest {

    static final String DIRTY_KEY = "article:view:dirty";
    static final String FLUSH_LOCK_KEY = "article:view:flush:lock";

    @Mock
    ArticleRepository articleRepository;
    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    ValueOperations<String, String> valueOperations;
    @Mock
    SetOperations<String, String> setOperations;
    @Mock
    RedissonClient redissonClient;
    @Mock
    RLock rLock;

    ArticleViewService articleViewService;

    @BeforeEach
    void setUp() {
        articleViewService = new ArticleViewService(articleRepository, redisTemplate, redissonClient);
    }

    @Test
    @DisplayName("createArticle: 저장 후 지연 증가분 키를 지우고 dirty 집합에서도 제거한다")
    void createArticle_resetsRedisState() {
        Article saved = mock(Article.class);
        given(saved.getId()).willReturn(5L);
        given(articleRepository.save(any(Article.class))).willReturn(saved);
        given(redisTemplate.opsForSet()).willReturn(setOperations);

        Long id = articleViewService.createArticle("t", "c");

        assertThat(id).isEqualTo(5L);
        verify(redisTemplate).delete("article:view:delta:5");
        verify(setOperations).remove(DIRTY_KEY, "5");
    }

    @Test
    @DisplayName("increaseViewCount: Redis INCR + dirty 등록만 하고 DB 는 건드리지 않는다")
    void increaseViewCount_onlyTouchesRedis() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);

        articleViewService.increaseViewCount(7L);

        verify(valueOperations).increment("article:view:delta:7");
        verify(setOperations).add(DIRTY_KEY, "7");
        verifyNoInteractions(articleRepository);
    }

    @Test
    @DisplayName("flushViewCounts: 락 획득 실패 시 아무 것도 안 하고 0을 반환한다 (unlock 도 안 함)")
    void flush_lockNotAcquired_returnsZero() throws InterruptedException {
        given(redissonClient.getLock(FLUSH_LOCK_KEY)).willReturn(rLock);
        given(rLock.tryLock(0, 5, TimeUnit.SECONDS)).willReturn(false);

        int flushed = articleViewService.flushViewCounts();

        assertThat(flushed).isZero();
        verifyNoInteractions(articleRepository);
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("flushViewCounts: dirty 집합이 비어있으면 0을 반환하고 락은 해제한다")
    void flush_noDirtyIds_returnsZeroAndUnlocks() throws InterruptedException {
        given(redissonClient.getLock(FLUSH_LOCK_KEY)).willReturn(rLock);
        given(rLock.tryLock(0, 5, TimeUnit.SECONDS)).willReturn(true);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.members(DIRTY_KEY)).willReturn(Collections.emptySet());

        int flushed = articleViewService.flushViewCounts();

        assertThat(flushed).isZero();
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("flushViewCounts: dirty id 를 SREM 먼저 한 뒤 GETDEL 로 델타를 가져와 DB 에 반영한다")
    void flush_appliesDeltaToDb_inCorrectOrder() throws InterruptedException {
        given(redissonClient.getLock(FLUSH_LOCK_KEY)).willReturn(rLock);
        given(rLock.tryLock(0, 5, TimeUnit.SECONDS)).willReturn(true);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.members(DIRTY_KEY)).willReturn(Set.of("3"));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete("article:view:delta:3")).willReturn("42");
        given(articleRepository.increaseViewCountBy(3L, 42L)).willReturn(1);

        int flushed = articleViewService.flushViewCounts();

        assertThat(flushed).isEqualTo(1);
        // SREM 이 GETDEL 보다 먼저 실행돼야 flush 도중 들어온 조회를 잃지 않는다.
        InOrder order = inOrder(setOperations, valueOperations);
        order.verify(setOperations).remove(DIRTY_KEY, "3");
        order.verify(valueOperations).getAndDelete("article:view:delta:3");
        verify(articleRepository).increaseViewCountBy(3L, 42L);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("flushViewCounts: 델타가 없거나(null) 0이면 DB 를 건드리지 않는다")
    void flush_zeroOrMissingDelta_skipsDb() throws InterruptedException {
        given(redissonClient.getLock(FLUSH_LOCK_KEY)).willReturn(rLock);
        given(rLock.tryLock(0, 5, TimeUnit.SECONDS)).willReturn(true);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.members(DIRTY_KEY)).willReturn(Set.of("9"));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete("article:view:delta:9")).willReturn(null);

        int flushed = articleViewService.flushViewCounts();

        assertThat(flushed).isZero();
        verifyNoInteractions(articleRepository);
    }

    @Test
    @DisplayName("flushViewCounts: DB 반영이 0행이면(게시글 삭제됨) flush 카운트에 포함하지 않는다")
    void flush_articleGone_notCountedAsFlushed() throws InterruptedException {
        given(redissonClient.getLock(FLUSH_LOCK_KEY)).willReturn(rLock);
        given(rLock.tryLock(0, 5, TimeUnit.SECONDS)).willReturn(true);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.members(DIRTY_KEY)).willReturn(Set.of("404"));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete("article:view:delta:404")).willReturn("3");
        given(articleRepository.increaseViewCountBy(404L, 3L)).willReturn(0);

        int flushed = articleViewService.flushViewCounts();

        assertThat(flushed).isZero();
    }

    @Test
    @DisplayName("flushViewCounts: 처리 중 예외가 나도 락은 반드시 해제한다")
    void flush_alwaysUnlocksEvenOnException() throws InterruptedException {
        given(redissonClient.getLock(FLUSH_LOCK_KEY)).willReturn(rLock);
        given(rLock.tryLock(0, 5, TimeUnit.SECONDS)).willReturn(true);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.members(DIRTY_KEY)).willThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> articleViewService.flushViewCounts())
                .isInstanceOf(RuntimeException.class);

        verify(rLock).unlock();
    }

    @Test
    @DisplayName("flushViewCounts: 락 대기 중 인터럽트되면 0을 반환하고 인터럽트 상태를 복원한다")
    void flush_interruptedWhileWaitingForLock_returnsZero() throws InterruptedException {
        given(redissonClient.getLock(FLUSH_LOCK_KEY)).willReturn(rLock);
        given(rLock.tryLock(0, 5, TimeUnit.SECONDS)).willThrow(new InterruptedException());

        int flushed = articleViewService.flushViewCounts();

        assertThat(flushed).isZero();
        assertThat(Thread.interrupted()).isTrue(); // 확인과 동시에 인터럽트 플래그 소모(다음 테스트에 영향 안 줌)
    }
}
