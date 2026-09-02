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
        Long result = redis.execute(
                issueScript,
                List.of(stockKey(couponId), issuedKey(couponId)),
                String.valueOf(userId));

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

        try {
            couponIssueProcessor.persistIssue(couponId, userId);
        } catch (RuntimeException e) {
            // DB 반영 실패 -> Redis 를 발급 이전 상태로 되돌린다.
            redis.opsForValue().increment(stockKey(couponId));
            redis.opsForSet().remove(issuedKey(couponId), String.valueOf(userId));
            throw e;
        }
    }
}
