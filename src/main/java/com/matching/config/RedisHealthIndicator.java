package com.matching.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component("redisMatchingHealth")
@Slf4j
public class RedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redisTemplate;
    private volatile boolean healthy = true;

    public RedisHealthIndicator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Health health() {
        try {
            String result = redisTemplate.getConnectionFactory().getConnection().ping();
            if (!"PONG".equalsIgnoreCase(result)) {
                healthy = false;
                return Health.down().withDetail("ping", result).build();
            }
            healthy = true;
            return Health.up().build();
        } catch (Exception e) {
            healthy = false;
            log.error("Redis health check failed", e);
            return Health.down(e).build();
        }
    }

    public boolean isHealthy() {
        return healthy;
    }
}