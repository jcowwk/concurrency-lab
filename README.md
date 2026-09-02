# concurrency-lab

동시성 / 데이터 정합성 문제를 직접 재현하고 단계별로 해결하는 과정을 기록하는 학습용 프로젝트.

## 기술 스택
- Java 21, Spring Boot 3.3
- PostgreSQL (JPA/Hibernate)
- Redis (뒤쪽 단계에서 분산락 / 원자적 연산에 사용)
- Docker Compose

## 도메인
1. 선착순 한정수량 쿠폰 발급 (수량 제한, 유저당 1회)
2. 게시글 조회수 증가 (동시 조회 시 카운트 유실 방지)

## 실행
```bash
docker compose up -d          # postgres, redis
./gradlew bootRun
```

## 진행 단계 (커밋 순서)
1. `feat: 쿠폰 발급 API 초기 구현 (동시성 미고려)`
2. `feat: 게시글 조회수 증가 API 초기 구현 (동시성 미고려)`
3. `test: 쿠폰 발급 / 조회수 증가 동시성 문제 재현 테스트` — ExecutorService + start gate 로 동시 요청, 올바른 결과를 단언(naive 에서 실패 = 재현)
4. `fix(coupon): 중복 발급 방지 - 유니크 제약`
5. `fix(coupon): 초과 발급 방지 - 비관적 락`
6. `fix(article): 조회수 유실 방지 - 원자적 UPDATE` ← main
7. Redis 분산락(Redisson) → Redis 원자연산 / Lua (예정)

각 단계의 트레이드오프 정리는 커밋 메시지 본문과 이 README 에 누적한다.

## 재현 결과 (naive)

| 문제 | 시나리오 | 기대 | naive 결과 |
|---|---|---|---|
| 쿠폰 초과 발급 | 서로 다른 유저 300명 동시 발급 (수량 100) | 100건 | 발급이력 300건 / issuedCount ~34 (거부 0건) |
| 쿠폰 중복 발급 | 같은 유저 100회 동시 요청 | 1건 | 발급이력 ~10건 |
| 조회수 유실 | 1000회 동시 조회 | 1000 | ~100 (예외 없이 유실) |

숫자는 스레드 스케줄링에 따라 실행마다 달라진다. 방향(초과 / 중복 / 유실)은 항상 재현된다.

현재 `main` 기준 `CouponConcurrencyTest`, `ArticleViewConcurrencyTest` 전부 GREEN
(초과 발급 100/100·200 거부, 중복 발급 1건, 조회수 1000/1000 유실 0).

## 초과 발급 해결책 비교 (브랜치)

같은 재현 테스트(`CouponConcurrencyTest`)를 서로 다른 방식으로 통과시킨다. 공통 베이스는 유니크 제약 커밋(4).

| 브랜치 | 방식 | 정합성 | 성능 / 처리량 | 장애 시나리오 | 적합 상황 |
|---|---|---|---|---|---|
| `solution/pessimistic-lock` (= main) | `SELECT ... FOR UPDATE` 로 쿠폰 row 배타 락 | 강함 | 같은 쿠폰 발급이 완전 직렬화. 락 구간에 INSERT+인덱스 갱신 포함 → 커넥션 점유 김 | 락 대기 중 커넥션 풀 고갈, 트랜잭션 장기화 시 데드락/타임아웃. `lock timeout` 설정 필요 | 핫 로우, 단일 DB, 로직이 단순하지 않을 때 |
| `solution/optimistic-lock` | `@Version` + 트랜잭션 밖 재시도 루프 | 강함 (version CAS) | 충돌 드물면 락 대기 0 으로 유리. 핫 로우에선 재시도 폭증 → 처리량 급락, DB 부하↑ | 재시도가 커넥션 반복 점유. 백오프/재시도 한도 튜닝 필수 | 경합 낮은 자원(유저별 데이터). 선착순 쿠폰엔 부적합 |
| `solution/atomic-update` | `UPDATE ... SET issued_count = issued_count + 1 WHERE issued_count < total` | 강함 (한 문장 원자적) | 가장 우수. 행 락 유지 시간 최소, 재시도 없음, 엔티티 로딩/더티체킹 생략 | 거의 없음. `@Modifying` 은 영속성 컨텍스트 우회 → 같은 tx 내 엔티티 재사용 주의 | 로직이 SQL 한 줄로 표현 가능할 때 (단순 카운터/재고) |

정리: 단순 카운터형 상한 관리라면 **원자적 UPDATE** 가 1순위. 도메인 규칙이 복잡해 한 문장에 못 담으면 **비관적 락**. **낙관적 락** 은 이 도메인(핫 로우 선착순)엔 맞지 않고, 경합이 낮은 일반 엔티티 수정에 쓴다.
