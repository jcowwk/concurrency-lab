# concurrency-lab

동시성 / 데이터 정합성 문제를 직접 재현하고 단계별로 해결하는 과정을 기록하는 학습용 프로젝트.

## 기술 스택
- Java 21, Spring Boot 3.3
- PostgreSQL (JPA/Hibernate)
- Redis — 원자적 연산(Lua), 분산락(Redisson), write-back 카운터
- Docker Compose

## 도메인
1. 선착순 한정수량 쿠폰 발급 (수량 제한, 유저당 1회)
2. 게시글 조회수 증가 (동시 조회 시 카운트 유실 방지)

## 문서
- [docs/study-guide.md](docs/study-guide.md) — 무엇을 어떤 순서로 보고, 어떻게 실행/로그 확인/테스트하나
- [docs/portfolio-summary.md](docs/portfolio-summary.md) — 문제·해결·성과(전/후 수치) + 면접용 판단 과정 설명
- [scripts/README.md](scripts/README.md) — k6 / hey 부하 테스트 + Redis 장애 주입 훈련
- 각 소스 파일 상단의 `STAR` 주석 — 파일별 상황/과제/조치/결과

## 실행
```bash
docker compose up -d          # postgres(5432), redis(6380)
./gradlew bootRun

# 동시성 재현/검증 테스트
./gradlew cleanTest test --tests '*ConcurrencyTest'
```

## 진행 단계 (커밋 순서)
1. `feat: 쿠폰 발급 API 초기 구현 (동시성 미고려)`
2. `feat: 게시글 조회수 증가 API 초기 구현 (동시성 미고려)`
3. `test: 쿠폰 발급 / 조회수 증가 동시성 문제 재현 테스트` — ExecutorService + start gate 로 동시 요청, 올바른 결과를 단언(naive 에서 실패 = 재현)
4. `fix(coupon): 중복 발급 방지 - 유니크 제약`
5. `fix(coupon): 초과 발급 방지 - 비관적 락` (DB 계열 비교 브랜치의 채택안이었음)
6. `fix(article): 조회수 유실 방지 - 원자적 UPDATE`
7. `chore: Redis 인프라 추가`
8. Merge `solution/redis-lua` — 쿠폰 발급을 Redis Lua 로 전환 (main 최종)
9. `feat(article): 조회수 Redis write-back + 주기적 flush` ← main 최종

`main` 은 "Redis 를 앞단에 둔" 최종 아키텍처. DB 계열/락 계열 대안은 아래 `solution/*` 브랜치로 보존한다.
각 단계의 트레이드오프는 커밋 메시지 본문과 이 README 에 누적.

### 브랜치 목록

| 브랜치 | 내용 |
|---|---|
| `main` | 쿠폰 = Redis Lua, 조회수 = Redis write-back |
| `solution/pessimistic-lock` | 쿠폰 초과 발급 = `SELECT ... FOR UPDATE` |
| `solution/optimistic-lock` | 쿠폰 초과 발급 = `@Version` + 재시도 |
| `solution/atomic-update` | 쿠폰 초과 발급 = 조건부 원자적 UPDATE |
| `solution/redis-redisson-lock` | 쿠폰 발급 = Redisson 분산락 |
| `solution/redis-lua` | 쿠폰 발급 = Redis Lua (main 에 병합됨) |
| `solution/article-db-atomic` | 조회수 = DB 원자적 UPDATE (main 이전 방식) |

## 재현 결과 (naive)

| 문제 | 시나리오 | 기대 | naive 결과 |
|---|---|---|---|
| 쿠폰 초과 발급 | 서로 다른 유저 300명 동시 발급 (수량 100) | 100건 | 발급이력 300건 / issuedCount ~34 (거부 0건) |
| 쿠폰 중복 발급 | 같은 유저 100회 동시 요청 | 1건 | 발급이력 ~10건 |
| 조회수 유실 | 1000회 동시 조회 | 1000 | ~100 (예외 없이 유실) |

숫자는 스레드 스케줄링에 따라 실행마다 달라진다. 방향(초과 / 중복 / 유실)은 항상 재현된다.

현재 `main` 기준 `CouponConcurrencyTest`, `ArticleViewConcurrencyTest` 전부 GREEN
(초과 발급 100/100·200 거부, 중복 발급 1건, 조회수는 `flushViewCounts()` 반영 후 1000/1000 유실 0).

## 쿠폰 초과/중복 발급 해결책 비교 (브랜치)

같은 재현 테스트(`CouponConcurrencyTest`)를 서로 다른 방식으로 통과시킨다.
DB 계열 3개는 유니크 제약 커밋(4)이, Redis 계열 2개는 Redis 인프라 커밋(7)이 공통 베이스.
모든 브랜치에서 초과 발급 100/100·200 거부, 중복 발급 1건으로 GREEN.

### DB 계열

| 브랜치 | 방식 | 정합성 | 성능 / 처리량 | 장애 시나리오 | 적합 상황 |
|---|---|---|---|---|---|
| `solution/pessimistic-lock` (= main) | `SELECT ... FOR UPDATE` 로 쿠폰 row 배타 락 | 강함 | 같은 쿠폰 발급이 완전 직렬화. 락 구간에 INSERT+인덱스 갱신 포함 → 커넥션 점유 김 | 락 대기 중 커넥션 풀 고갈, 트랜잭션 장기화 시 데드락/타임아웃. `lock timeout` 설정 필요 | 핫 로우, 단일 DB, 로직이 단순하지 않을 때 |
| `solution/optimistic-lock` | `@Version` + 트랜잭션 밖 재시도 루프 | 강함 (version CAS) | 충돌 드물면 락 대기 0 으로 유리. 핫 로우에선 재시도 폭증 → 처리량 급락, DB 부하↑ | 재시도가 커넥션 반복 점유. 백오프/재시도 한도 튜닝 필수 | 경합 낮은 자원(유저별 데이터). 선착순 쿠폰엔 부적합 |
| `solution/atomic-update` | `UPDATE ... SET issued_count = issued_count + 1 WHERE issued_count < total` | 강함 (한 문장 원자적) | DB 계열 중 최고. 행 락 유지 시간 최소, 재시도 없음, 엔티티 로딩/더티체킹 생략 | 거의 없음. `@Modifying` 은 영속성 컨텍스트 우회 → 같은 tx 내 엔티티 재사용 주의 | 로직이 SQL 한 줄로 표현 가능할 때 (단순 카운터/재고) |

### Redis 계열 (다중 인스턴스 / DB 부하 분산이 목적)

| 브랜치 | 방식 | 정합성 | 성능 / 처리량 | 장애 시나리오 | 적합 상황 |
|---|---|---|---|---|---|
| `solution/redis-redisson-lock` | `RLock("coupon:lock:{id}")` 분산락, 락 밖에서 DB 트랜잭션 수행 | 강함. 단 lease 만료+STW 겹치면 두 노드 동시 진입 가능 → DB 유니크 제약이 최종 방어선 | 같은 쿠폰은 여전히 직렬화 + 매 요청 Redis 왕복 추가 | Redis 다운 = 발급 불가(가용성 종속). 단일 노드 SPOF, 센티넬/클러스터 시 Redlock 안전성 논쟁 | 앱 인스턴스가 여러 대라 JVM 락으로 부족하고, 로직은 DB 에 둬야 할 때 |
| `solution/redis-lua` | `SISMEMBER→GET/DECR→SADD` 를 Lua 스크립트로 원자 실행, DB 는 사본 | 판정 자체는 강함(원자적). 판정 주체가 Redis → DB 는 최종 일관성. AOF/RDB 유실 시 재고 소실 | 최고. O(1), 락/재시도 없음, 발급 경로에서 DB 제거(큐 비동기화) 가능 | Redis SPOF, 보상 실패 시 Redis-DB drift, 스크립트 예외 시 부분 반영 | 순간 트래픽이 DB 한계를 넘고, 최종 일관성을 감내할 수 있을 때 |

정리:
- 단일 DB 로 충분하면 **원자적 UPDATE** 가 1순위(단순 카운터/재고). 도메인 규칙이 복잡해 한 문장에 못 담으면 **비관적 락**.
- **낙관적 락** 은 핫 로우 선착순엔 부적합, 경합 낮은 일반 엔티티 수정용.
- 앱 인스턴스가 여러 대면 **Redisson 분산락**, DB 자체가 병목이면 **Redis Lua** + 비동기 반영. 둘 다 운영 복잡도와 장애 표면이 커지므로 "DB 로 안 될 때" 꺼내는 카드.

## 조회수 증가 해결책 비교

| 방식 | 브랜치 | 정합성 | 성능 | 장애 시나리오 | 비고 |
|---|---|---|---|---|---|
| DB 원자적 UPDATE | `solution/article-db-atomic` | 강함. 매 조회가 즉시 DB 에 반영 | 매 조회 = DB 쓰기 1건. 조회 트래픽이 높으면 DB 쓰기 부하가 그대로 | 거의 없음 | 조회량이 감당 가능한 수준일 때 가장 단순·안전 |
| Redis write-back (main) | `main` | 최종 일관성. `INCR` 은 원자적이라 유실 0, DB 는 flush 주기만큼 뒤처짐 | 매 조회 = Redis `INCR` 1회(O(1)). DB 쓰기는 주기적 배치로 대폭 감소 | Redis 유실 시 마지막 flush~장애 사이 증가분 손실. flush 중 인스턴스 다운 시 부분 반영(증분이라 다음 flush 가 나머지 처리) | 조회량이 많아 DB 쓰기를 줄여야 할 때. `article:view:delta:{id}` 누적 → dirty set → `@Scheduled` + 분산락으로 한 인스턴스만 flush |

트레이드오프의 핵심: **"조회수가 몇 초 뒤처져도 되는가"** 를 받아들이면 DB 쓰기를 조회당 1건 → flush당 1건으로 줄일 수 있다. 정확한 실시간 값이 필요하면 DB 원자적 UPDATE.
