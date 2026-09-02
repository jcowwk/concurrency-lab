-- ═══ 쿠폰 발급 판정 스크립트 (STAR) ═══════════════════════════════════════════
--  S 여러 요청이 같은 쿠폰의 재고를 동시에 읽고 각자 "아직 남았네" 하고 차감 → 초과 발급.
--  T 검사(남았나? 이미 받았나?)와 차감을 쪼개지지 않게 한 덩어리로 실행해야 한다.
--  A 이 로직 전체를 Lua 스크립트로 Redis 안에서 실행. Redis 는 명령을 단일 스레드로
--    처리하고 스크립트가 통째로 원자 실행되므로, 검사~차감 사이에 다른 요청이 못 낀다.
--  R 300 동시요청 → 정확히 100건만 1 반환. 락·재시도 없이 O(1). (이 브랜치가 main 에 병합됨)
-- ══════════════════════════════════════════════════════════════════════════════
--
-- KEYS[1] = coupon:stock:{couponId}   (남은 수량, 정수 문자열)
-- KEYS[2] = coupon:issued:{couponId}  (발급받은 userId 집합)
-- ARGV[1] = userId
--
-- 반환:  1 = 발급 가능   0 = 소진   -1 = 이미 발급   -2 = 미초기화(쿠폰 없음)

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

local stock = redis.call('GET', KEYS[1])
if stock == false then
    return -2
end

if tonumber(stock) <= 0 then
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
