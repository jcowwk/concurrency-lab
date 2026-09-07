package com.example.concurrencylab.common;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Redis 가 응답하지 않으면(연결 실패 / 명령 타임아웃 / 스크립트 오류) 발급 판정 자체를
     * 할 수 없다. 500 대신 503(일시적)으로 내린다. 발급이 안 됐을 뿐 정합성은 안 깨진다(fail-safe).
     * 복구: Redis 재기동 + (데이터 유실 시) POST /api/coupons/{id}/redis-rebuild.
     *
     * 이 앱에서 컨트롤러까지 올라오는 Redis 예외는 쿠폰 발급/재구축·조회수 경로에서만 나온다
     * (DB 예외는 서비스에서 잡아 IllegalStateException 으로 변환).
     */
    @ExceptionHandler({
            RedisConnectionFailureException.class,
            RedisSystemException.class,
            QueryTimeoutException.class
    })
    public ProblemDetail handleRedisDown(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "일시적으로 발급 처리가 불가합니다. 잠시 후 다시 시도해주세요.");
    }
}
