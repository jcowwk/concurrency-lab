# 학습 가이드 — 무엇을 보고, 어떻게 실행하나

이 프로젝트는 "동시성 문제를 일부러 재현 → 여러 해법을 브랜치로 비교 → 선택"의 기록이다.
아래 순서대로 따라가면 코드와 실행·검증 방법을 한 번에 익힐 수 있다.

- 각 해법의 전/후 수치와 트레이드오프: [portfolio-summary.md](portfolio-summary.md)
- 소스 파일마다 상단에 `STAR` 주석(상황/과제/조치/결과)이 달려 있다.

---

## 0. 사전 준비

| 필요 | 확인 |
|---|---|
| JDK 21 | `java -version` (17+ 면 동작, 프로젝트는 21) |
| Docker | `docker version` |

```bash
docker compose up -d          # PostgreSQL(5432), Redis(6380) 기동
docker compose ps             # 두 컨테이너가 Up 인지 확인
```

- Redis 호스트 포트는 **6380** (로컬에 다른 Redis 가 6379 를 쓰고 있어 옮김). `application.yml` 의 `spring.data.redis.port` 와 `docker-compose.yml` 의 매핑이 같이 6380.
- 스키마는 `ddl-auto: create-drop` → 앱/테스트가 뜰 때마다 테이블을 새로 만든다. 데이터 초기화 신경 안 써도 됨.

---

## 1. 테스트로 문제와 해결을 확인하기 (제일 쉬움)

재현/검증 테스트는 `*ConcurrencyTest` 두 개다.

```bash
# 전체 동시성 테스트 (cleanTest 를 붙여야 캐시 무시하고 재실행됨)
./gradlew cleanTest test --tests '*ConcurrencyTest'

# 하나만
./gradlew cleanTest test --tests 'com.example.concurrencylab.coupon.CouponConcurrencyTest'
./gradlew cleanTest test --tests 'com.example.concurrencylab.article.ArticleViewConcurrencyTest'

# 전체 테스트
./gradlew cleanTest test
```

### 결과 보는 3가지 방법

1. **콘솔의 진단 줄** — 각 테스트가 assert 전에 실제 수치를 찍는다:
   ```
   [초과 발급]   요청 300 / issuedCount=100 / 발급이력 row=100 / 성공=100 / 실패=200
   [중복 발급]   요청 100 / userId=1 발급이력 row=1 / 성공=1 / 실패=99
   [조회수 유실] 요청 1000 / viewCount=1000 / 유실=0 / 성공=1000 / 실패=0
   ```
   콘솔에 안 보이면:
   ```bash
   ./gradlew cleanTest test --tests '*ConcurrencyTest' -i \
     | grep -E '\[초과|\[중복|\[조회수|FAILED|expected|but was'
   ```
2. **HTML 리포트** — `open build/reports/tests/test/index.html` → 테스트 클릭 → 진단 출력 + 스택트레이스.
3. **원시 XML** — `build/test-results/test/*.xml` 의 `message=`, `<system-out>`.

### `main` 에서 기대되는 결과

| 테스트 | 기대 |
|---|---|
| `초과_발급이_발생하지_않아야_한다` | GREEN — 발급 100 / 거부 200 |
| `중복_발급이_발생하지_않아야_한다` | GREEN — 발급 1 / 실패 99 |
| `조회수가_유실되지_않아야_한다` | GREEN — `flushViewCounts()` 후 viewCount 1000 |

`BUILD SUCCESSFUL` 이면 3개 다 통과. (naive 커밋에서 돌리면 `BUILD FAILED` 가 정상 — 문제가 재현됐다는 뜻)

### 통합 테스트 vs 단위 테스트

| | 통합 테스트 (`@SpringBootTest`) | 단위 테스트 (JUnit + Mockito) |
|---|---|---|
| 대상 | `*ConcurrencyTest`, `CouponRedisRecoveryTest` | `*ServiceTest`, `CouponIssueProcessorTest` (`.../service/` 패키지) |
| 필요 인프라 | PostgreSQL + Redis (`docker compose up -d`) | **없음** |
| 검증 대상 | 실제 동시성 결과(락/원자성이 진짜 지켜지나), 장애 복구 | 분기 로직(어떤 입력에 어떤 예외, 어떤 순서로 호출하나) |
| 실행 |  `./gradlew cleanTest test --tests '*ConcurrencyTest'` | `./gradlew cleanTest test --tests '*.service.*'` (Docker 꺼져 있어도 통과) |

동시성 버그(lost update, race)는 mock으로 재현이 안 되므로 통합 테스트가 필수고, 예외 분기·보상
로직(`issue()` 의 -1/-2/0/1 처리, Redis 보상, flush 의 SREM→GETDEL 순서 등)은 단위 테스트가
훨씬 빠르고 정확하게 짚어준다. 둘 다 있어야 완전하다.

---

## 2. 앱 띄우고 REST 로 직접 때려보기

### 실행

```bash
./gradlew bootRun
# 로그에 "Started ConcurrencyLabApplication" 뜨면 준비 완료 (포트 8080)
```

### 로그 보는 법

- `application.yml` 에서 이미 켜둔 것:
  - `spring.jpa.show-sql: true`, `logging.level.org.hibernate.SQL: debug` → **실제 나가는 SQL** 이 콘솔에 찍힘
  - `logging.level.org.hibernate.orm.jdbc.bind: trace` → SQL 의 `?` 에 **바인딩된 실제 값**
- SQL 로그가 시끄러우면 잠깐 `show-sql: false` 로.
- Redis 상태 직접 확인:
  ```bash
  docker exec -it concurrency-lab-redis redis-cli
  > KEYS coupon:*
  > GET coupon:stock:1
  > SMEMBERS coupon:issued:1
  > GET article:view:delta:1
  > SMEMBERS article:view:dirty
  ```
- DB 직접 확인:
  ```bash
  docker exec -it concurrency-lab-postgres psql -U lab -d concurrency_lab
  => SELECT id, total_quantity, issued_count FROM coupon;
  => SELECT count(*) FROM coupon_issue WHERE coupon_id = 1;
  => SELECT id, view_count FROM article;
  ```

### 동시 요청 재현 (터미널 B)

```bash
# 재고 100 쿠폰 생성
COUPON_ID=$(curl -s -XPOST localhost:8080/api/coupons \
  -H 'Content-Type: application/json' -d '{"name":"선착순","totalQuantity":100}')
echo "couponId=$COUPON_ID"

# 서로 다른 유저 300명 동시 발급 (병렬 50) → 응답 코드 분포
seq 1 300 | xargs -P 50 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -XPOST localhost:8080/api/coupons/issue \
  -H 'Content-Type: application/json' \
  -d "{\"couponId\":$COUPON_ID,\"userId\":{}}" | sort | uniq -c

# 결과: 200 이 100개, 409(소진) 가 200개  →  issued_count 와 이력 row 도 100
docker exec concurrency-lab-postgres psql -U lab -d concurrency_lab -c \
  "SELECT issued_count FROM coupon WHERE id=$COUPON_ID;
   SELECT count(*) FROM coupon_issue WHERE coupon_id=$COUPON_ID;"

# 조회수: 1000회 동시 조회 → 최대 2초 뒤(flush 주기) DB 반영
ARTICLE_ID=$(curl -s -XPOST localhost:8080/api/articles \
  -H 'Content-Type: application/json' -d '{"title":"t","content":"c"}')
seq 1 1000 | xargs -P 50 -I{} curl -s -o /dev/null \
  -XPOST localhost:8080/api/articles/$ARTICLE_ID/views
sleep 3
docker exec concurrency-lab-postgres psql -U lab -d concurrency_lab -c \
  "SELECT view_count FROM article WHERE id=$ARTICLE_ID;"   # 1000
```

> naive 커밋(`git checkout 8e83695` 등)에서 같은 스크립트를 돌리면 발급이 300건 다 나가고,
> 조회수는 1000 보다 한참 작게 나온다. 이게 "재현".

---

## 3. 코드 읽는 순서

### 공통

| 순서 | 파일 | 왜 |
|---|---|---|
| 1 | [ConcurrencyRunner.java](../src/test/java/com/example/concurrencylab/support/ConcurrencyRunner.java) | 재현 도구. `startGate` 로 N개 스레드를 같은 순간에 임계구역에 밀어넣는다 |
| 2 | [CouponConcurrencyTest.java](../src/test/java/com/example/concurrencylab/coupon/CouponConcurrencyTest.java) · [ArticleViewConcurrencyTest.java](../src/test/java/com/example/concurrencylab/article/ArticleViewConcurrencyTest.java) | "올바른 결과"를 단언 → naive 에서 실패 = 문제 증명 |

### 쿠폰 (초과 발급 / 중복 발급)

| 순서 | 파일 | 포인트 |
|---|---|---|
| 1 | [CouponIssueService.java](../src/main/java/com/example/concurrencylab/coupon/service/CouponIssueService.java) (naive 커밋 `8e83695`) | `조회 → 검사 → 저장` 이 원자적이지 않음 (check-then-act) |
| 2 | [CouponIssue.java](../src/main/java/com/example/concurrencylab/coupon/domain/CouponIssue.java) | `(coupon_id, user_id)` 유니크 제약 = 중복 발급 최종 방어선 |
| 3 | [lua/coupon_issue.lua](../src/main/resources/lua/coupon_issue.lua) | 중복검사→재고차감→발급기록을 한 덩어리로 원자 실행 |
| 4 | [CouponIssueService.java](../src/main/java/com/example/concurrencylab/coupon/service/CouponIssueService.java) (main) | Lua 판정 → DB 사본 기록 → 실패 시 Redis 보상 |
| 5 | [CouponIssueProcessor.java](../src/main/java/com/example/concurrencylab/coupon/service/CouponIssueProcessor.java) | DB 기록을 별도 `@Transactional` 빈으로 분리한 이유(=락/보상은 트랜잭션 밖) |
| 6 | [CouponIssueServiceTest.java](../src/test/java/com/example/concurrencylab/coupon/service/CouponIssueServiceTest.java) · [CouponIssueProcessorTest.java](../src/test/java/com/example/concurrencylab/coupon/service/CouponIssueProcessorTest.java) | 단위 테스트로 4/5 의 분기(-1/-2/0/1, 보상, 유니크 제약 변환)를 Mockito 로 하나씩 확인 |

### 게시글 조회수 (카운트 유실)

| 순서 | 파일 | 포인트 |
|---|---|---|
| 1 | [Article.java](../src/main/java/com/example/concurrencylab/article/domain/Article.java) `increaseViewCount()` | read-modify-write → lost update (naive) |
| 2 | [ArticleRepository.java](../src/main/java/com/example/concurrencylab/article/repository/ArticleRepository.java) | 증분 UPDATE `view_count = view_count + :delta` |
| 3 | [ArticleViewService.java](../src/main/java/com/example/concurrencylab/article/service/ArticleViewService.java) | 조회 = Redis `INCR` / 주기적 `flushViewCounts()` = 분산락 + SREM→GETDEL 순서 |
| 4 | [ArticleViewServiceTest.java](../src/test/java/com/example/concurrencylab/article/service/ArticleViewServiceTest.java) | 단위 테스트로 락 미획득/빈 dirty/예외 시 unlock 등 엣지케이스를 Mockito 로 확인 |

---

## 4. 브랜치별 학습

`main` 은 최종안(쿠폰=Redis Lua, 조회수=Redis write-back). 다른 해법은 브랜치로 보존.
각 브랜치는 **같은 재현 테스트**를 다른 방식으로 통과시킨다.

```bash
git branch -a                       # 브랜치 목록
git checkout solution/<이름>
docker compose up -d
./gradlew cleanTest test --tests '*ConcurrencyTest'
git checkout main
```

| 브랜치 | 보는 파일 | 핵심 개념 | 특징 |
|---|---|---|---|
| `solution/pessimistic-lock` | `CouponRepository.findByIdForUpdate` | `SELECT … FOR UPDATE` 행 배타 락 | 확실 / 완전 직렬화 |
| `solution/optimistic-lock` | `Coupon.@Version`, `CouponIssueService` 재시도 루프, `CouponIssueProcessor` | version CAS + 트랜잭션 밖 재시도 | 핫로우에서 재시도 폭증 (실측 2~3배 느림) |
| `solution/atomic-update` | `CouponRepository.increaseIssuedCountIfAvailable` | `UPDATE … WHERE issued_count < total` | DB 단독으로 가장 빠름 |
| `solution/redis-redisson-lock` | `CouponIssueService` `RLock`, `CouponIssueProcessor` | Redisson 분산락 | 다중 인스턴스 / 락 해제는 커밋 후 |
| `solution/redis-lua` | `lua/coupon_issue.lua` | Redis 원자 스크립트 | main 에 병합됨 |
| `solution/article-db-atomic` | `ArticleRepository.increaseViewCount`, `ArticleViewService` | 조회수 = DB 증분 UPDATE | 즉시 정합 / 조회당 DB 쓰기 1건 |

### 커밋 히스토리로 흐름 보기

```bash
git log --oneline --graph --all
git show 7795e3d      # 중복 발급 → 유니크 제약
git show c74cfd6      # 초과 발급 → 비관적 락
git show 088deca      # 초과/중복 → Redis Lua
git show aea07a1      # 조회수 → Redis write-back
```

---

## 5. 자주 겪는 것

| 증상 | 원인 / 해결 |
|---|---|
| `./gradlew test` 가 "UP-TO-DATE" 로 안 돌아감 | `cleanTest` 를 앞에 붙인다 |
| 테스트가 커넥션 거부로 실패 | `docker compose up -d` 안 함 / 포트 충돌 |
| Redis 관련 테스트만 실패 | 6380 포트에 우리 `concurrency-lab-redis` 가 떠 있는지 확인 |
| naive 커밋에서 `BUILD FAILED` | 정상. 문제가 재현되면 실패하도록 짠 테스트다 |
| 수치가 실행마다 다름 | 정상. race condition = 결과가 스레드 스케줄링에 의존 |
