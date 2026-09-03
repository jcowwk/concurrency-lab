# 부하 테스트 스크립트

동시성 해결책이 **부하 상황에서도** 정합성을 지키는지 검증한다.
성능 수치(처리량/지연)는 덤이고, 핵심은 "재고 100 초과 안 함 / 조회수 안 샘" 확인.

## 사전 준비

```bash
docker compose up -d
# SQL 로그는 꺼두는 게 좋다 (수치 왜곡 + 터미널 폭발)
#   application.yml → spring.jpa.show-sql: false, logging.level.org.hibernate.SQL: info
./gradlew bootRun
```

## k6 (권장)

```bash
brew install k6

# 쿠폰 초과 발급 — VU 200, 15초
k6 run scripts/k6-coupon-issue.js
k6 run -e VUS=300 -e DURATION=20s -e STOCK=100 scripts/k6-coupon-issue.js

# 조회수 — 5000회 요청
k6 run scripts/k6-article-views.js
k6 run -e VUS=100 -e ITERATIONS=10000 scripts/k6-article-views.js
```

실행이 끝나면 `teardown()` 이 검증용 psql 명령을 출력한다. 그대로 복사해 실행:
- 쿠폰: `issued_count` 와 `coupon_issue` 행 수가 `STOCK` 이하 & 서로 같아야 정상
- 조회수: flush 주기(~2s) 후 `view_count == 요청 수`

## hey (단순 처리량, body 고정)

```bash
brew install hey

# 조회수 - 게시글 1 생성 후
hey -n 5000 -c 100 -m POST http://localhost:8080/api/articles/1/views

# 쿠폰 중복 발급 (같은 userId 반복)
echo '{"couponId":1,"userId":99999}' > /tmp/dup.json
hey -n 1000 -c 50 -m POST -T application/json -D /tmp/dup.json \
  http://localhost:8080/api/coupons/issue
```

> `hey`/`ab` 는 요청 body 가 고정이라 "userId 를 매번 다르게" 하는 초과 발급 시나리오엔 부적합.
> 그건 k6 (`__VU`, `__ITER` 로 유일 값 생성) 또는 `seq | xargs -P curl` 를 쓴다.

## 전/후 비교

같은 스크립트를 naive 커밋에서도 돌려 대조한다.

```bash
git checkout 8e83695     # 쿠폰 동시성 미고려
./gradlew bootRun
k6 run scripts/k6-coupon-issue.js
# → 발급 이력이 STOCK 을 훨씬 초과 (초과 발급 재현)
git checkout main
```
