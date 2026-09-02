-- 선착순 쿠폰 발급 판정. Redis 는 명령을 단일 스레드로 처리하고
-- 스크립트 전체가 원자적으로 실행되므로, 아래 검사~차감 사이에 다른 요청이 끼어들 수 없다.
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
