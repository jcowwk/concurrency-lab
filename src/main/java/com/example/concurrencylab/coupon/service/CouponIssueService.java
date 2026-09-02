package com.example.concurrencylab.coupon.service;

import com.example.concurrencylab.coupon.domain.Coupon;
import com.example.concurrencylab.coupon.repository.CouponRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * ═══ 동시성 해결: 쿠폰 초과 발급 & 중복 발급 (STAR) ══════════════════════════════
 *
 *  S 상황  재고 100개 한정 선착순 쿠폰. 서로 다른 유저 300명이 "동시에" 발급 요청.
 *          + 같은 유저가 네트워크 재시도 등으로 동시에 여러 번 요청.
 *
 *  T 과제  · 초과 발급 0 (100개까지만, 101번째부터는 거부)
 *          · 같은 유저 중복 발급 0 (유저당 1장)
 *          · 재고 카운터 유실 0 (발급 이력 수 == issued_count)
 *
 *  A 조치  발급 "판정"(중복검사 → 재고차감 → 발급기록)을 Redis Lua 스크립트 하나로
 *          원자 실행한다(lua/coupon_issue.lua). Redis 는 명령을 단일 스레드로 돌리고
 *          스크립트가 통째로 원자 실행되므로, 락도 재시도도 없이 check-and-decrement 성립.
 *          DB 는 판정 결과를 뒤따라 기록하는 사본(CouponIssueProcessor).
 *          (coupon_id, user_id) 유니크 제약은 Redis-DB 불일치 대비 최종 방어선으로 유지.
 *
 *  R 결과  (재현 테스트 CouponConcurrencyTest, 로컬/상대비교)
 *          ┌───────────────┬───────── 전(naive) ─────────┬──── 후(Redis Lua) ────┐
 *          │ 초과 발급     │ 300건 발급(+200%), 거부 0    │ 100건 발급, 200건 거부 │
 *          │ 중복 발급     │ 유저당 9~10건               │ 1건                   │
 *          │ 재고 카운터   │ 34 (266 유실)              │ 100 (이력 수와 일치)   │
 *          │ 300건 처리시간│ —                          │ 0.37~0.65s            │
 *          └───────────────┴────────────────────────────┴───────────────────────┘
 *          비교 브랜치: 낙관적 락 1.16~1.25s / 비관적 락 0.72~0.89s / 원자 UPDATE 0.49~0.58s
 *
 *  주의점  · 재시도(낙관적 락)가 아니라 "보상"이 필요 → 트랜잭션 경계 밖(이 클래스)에서
 *            DB 예외를 받아 Redis 를 되돌린다. 그래서 issue() 에는 @Transactional 없음.
 * ══════════════════════════════════════════════════════════════════════════════
 */
@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final CouponIssueProcessor couponIssueProcessor;
    private final StringRedisTemplate redis;

    private RedisScript<Long> issueScript;

    @PostConstruct
    void loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/coupon_issue.lua")));
        script.setResultType(Long.class);
        this.issueScript = script;
    }

    private static String stockKey(Long couponId) {
        return "coupon:stock:" + couponId;
    }

    private static String issuedKey(Long couponId) {
        return "coupon:issued:" + couponId;
    }

    /**
     * 쿠폰 생성 시 Redis 에 재고 카운터를 심는다(캠페인 초기화).
     * SET 으로 덮어쓰고 발급 집합을 비워, 같은 id 로 재생성해도 상태가 초기화된다.
     */
    @Transactional
    public Long createCoupon(String name, int totalQuantity) {
        Long id = couponRepository.save(new Coupon(name, totalQuantity)).getId();
        redis.opsForValue().set(stockKey(id), String.valueOf(totalQuantity));
        redis.delete(issuedKey(id));
        return id;
    }

    /**
     * 쿠폰 발급 - Redis 원자적 연산(Lua).
     *
     * 발급 가부(중복/소진/차감)를 Lua 스크립트가 Redis 안에서 한 번에 판정한다.
     * Redis 는 명령을 단일 스레드로 처리하고 스크립트가 통째로 원자 실행되므로,
     * 락도 재시도도 없이 check-and-decrement 가 성립한다. O(1).
     *
     * DB 는 판정 결과를 뒤따라 기록하는 사본이다(최종 일관성). Lua 가 OK 를 준 뒤
     * DB 반영에 실패하면 Redis 재고/발급집합을 되돌린다(보상). 실서비스라면 이 DB 반영을
     * 큐로 넘겨 비동기 처리해 발급 경로에서 DB 를 완전히 빼는 방향으로 확장한다.
     */
    public void issue(Long couponId, Long userId) {
        // (1) 발급 판정 = Redis Lua 원자 실행. 이 한 줄 안에서 중복검사·재고차감·발급기록이 끝난다.
        //     여기가 임계구역이고, Redis 단일 스레드가 순서를 보장하므로 별도 락이 필요 없다.
        Long result = redis.execute(
                issueScript,
                List.of(stockKey(couponId), issuedKey(couponId)),
                String.valueOf(userId));

        // (2) 스크립트 반환코드 해석:  1=발급가능  0=소진  -1=이미발급  -2=쿠폰없음
        long code = result == null ? -2 : result;
        if (code == -1) {
            throw new IllegalStateException("이미 발급받은 쿠폰입니다. userId=" + userId);
        }
        if (code == -2) {
            throw new IllegalArgumentException("존재하지 않는 쿠폰: " + couponId);
        }
        if (code == 0) {
            throw new IllegalStateException("쿠폰이 모두 소진되었습니다. couponId=" + couponId);
        }

        // (3) Redis 가 OK 했으니 DB 에 사본 기록. 이미 재고는 Redis 에서 깎였다.
        try {
            couponIssueProcessor.persistIssue(couponId, userId);
        } catch (RuntimeException e) {
            // (4) DB 기록 실패 = Redis 와 DB 가 어긋남 → Redis 를 발급 이전으로 되돌린다(보상).
            //     낙관적 락처럼 "재시도"하면 안 된다. 이미 Redis 상태가 바뀌었기 때문.
            redis.opsForValue().increment(stockKey(couponId));                       // 재고 원복
            redis.opsForSet().remove(issuedKey(couponId), String.valueOf(userId));   // 발급집합에서 제거
            throw e;
        }
    }
}
