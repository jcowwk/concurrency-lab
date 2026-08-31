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
1. `feat: 쿠폰 발급 API 초기 구현 (동시성 미고려)` ← 현재
2. `test: 쿠폰 발급 동시성 문제 재현 테스트`
3. 해결책 단계별 적용 (DB 유니크 제약 → 비관적 락 → 낙관적 락 → 원자적 UPDATE → Redis 분산락 → Redis Lua)

각 단계의 트레이드오프 정리는 커밋 메시지 본문과 이 README 에 누적한다.
