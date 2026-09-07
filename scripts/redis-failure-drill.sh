#!/usr/bin/env bash
#
# Redis 장애 주입 훈련. 앱이 8080 에 떠 있는 상태에서 실행한다.
#   docker compose up -d && ./gradlew bootRun
#   bash scripts/redis-failure-drill.sh
#
# 두 가지 장애를 순서대로 재현한다.
#   Part A. 연결 실패 (docker stop) → 발급이 503 으로 응답 (fail-safe). AOF 로 재기동 시 자가 복구.
#   Part B. 데이터 유실 (FLUSHALL)  → 발급이 404 로 거부 → DB 이력으로 rebuild → 정상화.
#
# 주의: macOS 기본 bash 3.2 의 $(...) 중첩 파싱 버그를 피하려고 JSON 은 printf 로 만든다.
set -u

BASE=${BASE:-http://localhost:8080}
REDIS=concurrency-lab-redis
PGC=concurrency-lab-postgres

pg()  { docker exec "$PGC" psql -U lab -d concurrency_lab -tAc "$1" | tr -d '[:space:]'; }
rcli() { docker exec "$REDIS" redis-cli "$@"; }

# HTTP status code 만 반환
http_code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

create_coupon() { # $1=stock
  local body; body=$(printf '{"name":"drill","totalQuantity":%s}' "$1")
  curl -s -XPOST "$BASE/api/coupons" -H 'Content-Type: application/json' -d "$body"
}
issue() { # $1=couponId $2=userId  -> prints http code
  local body; body=$(printf '{"couponId":%s,"userId":%s}' "$1" "$2")
  http_code -XPOST "$BASE/api/coupons/issue" -H 'Content-Type: application/json' -d "$body"
}
rebuild() { # $1=couponId -> prints http code
  http_code -XPOST "$BASE/api/coupons/$1/redis-rebuild"
}

echo "▶ 재고 5 쿠폰 생성"
CID=$(create_coupon 5)
echo "  couponId=$CID"

echo "▶ 정상 발급 2건 (u1, u2)"
for u in 1 2; do echo "  u$u -> $(issue "$CID" "$u")"; done
echo "  DB 이력: $(pg "SELECT count(*) FROM coupon_issue WHERE coupon_id=$CID")"

echo
echo "═══ Part A. 연결 실패 ═══"
echo "▶ Redis 정지"; docker stop "$REDIS" >/dev/null
echo "▶ 발급 시도 u3 -> $(issue "$CID" 3)   (503 이면 fail-safe 정상)"
echo "  DB 이력: $(pg "SELECT count(*) FROM coupon_issue WHERE coupon_id=$CID")   (여전히 2 = 정합성 유지)"
echo "▶ Redis 재기동"; docker start "$REDIS" >/dev/null; sleep 3
echo "  AOF 자가복구 확인: coupon:stock:$CID = $(rcli GET "coupon:stock:$CID")   (값이 있으면 AOF 로 복구된 것)"
echo "▶ 발급 재시도 u3 -> $(issue "$CID" 3)"

echo
echo "═══ Part B. 데이터 유실 ═══"
echo "▶ FLUSHALL  (재기동+AOF 갭 / eviction / 빈 복제본 failover 흉내)"
rcli FLUSHALL >/dev/null
echo "  coupon:stock:$CID = '$(rcli GET "coupon:stock:$CID")'   (비어야 정상)"
echo "▶ 발급 시도 u4 -> $(issue "$CID" 4)   (404 = 쿠폰 없음으로 거부, fail-safe)"
echo "  DB 이력: $(pg "SELECT count(*) FROM coupon_issue WHERE coupon_id=$CID")   (변화 없음)"
echo "▶ DB 이력으로 Redis 재구축 -> $(rebuild "$CID")"
echo "  coupon:stock:$CID  = $(rcli GET "coupon:stock:$CID")   (= 5 - 발급수)"
echo "  coupon:issued:$CID = $(rcli SCARD "coupon:issued:$CID") 명"
echo "▶ 발급 재개 (이미 받은 u1 은 거부, 새 유저는 소진까지)"
for u in 1 4 5 6 7; do echo "  u$u -> $(issue "$CID" "$u")"; done
echo "  최종 DB 이력: $(pg "SELECT count(*) FROM coupon_issue WHERE coupon_id=$CID")   (5 = 초과 없음)"
echo "  중복 유저 수: $(pg "SELECT count(*)-count(DISTINCT user_id) FROM coupon_issue WHERE coupon_id=$CID")   (0)"
