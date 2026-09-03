package com.example.concurrencylab.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI concurrencyLabOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("concurrency-lab API")
                .version("v1")
                .description("""
                        선착순 쿠폰 발급 / 게시글 조회수 증가 API.
                        동시성 문제를 재현하고 해결하는 학습용 프로젝트.

                        - 쿠폰 발급: 재고 100 한정, 유저당 1회 (main: Redis Lua)
                        - 조회수: 동시 조회 시 유실 방지 (main: Redis write-back, flush 주기 약 2초)
                        """));
    }
}
