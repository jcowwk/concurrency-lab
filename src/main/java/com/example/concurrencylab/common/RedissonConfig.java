package com.example.concurrencylab.common;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 분산락용 RedissonClient. spring.data.redis.* 값을 그대로 사용한다.
 * (RedisTemplate 은 spring-boot-starter-data-redis 가 같은 값으로 자동 구성한다)
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                // 장애 시 빠르게 포기 (기본값은 재시도 3회 + 3s 타임아웃이라 체감이 느리다)
                .setConnectTimeout(2000)
                .setTimeout(2000)
                .setRetryAttempts(2)
                .setRetryInterval(300);
        return Redisson.create(config);
    }
}
