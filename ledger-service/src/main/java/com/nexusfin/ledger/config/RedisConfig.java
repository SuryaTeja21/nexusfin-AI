package com.nexusfin.ledger.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Redis caching and distributed synchronization.
 */
@Configuration
@ConditionalOnProperty(name = "nexusfin.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfig {
    // Spring Boot's RedisAutoConfiguration supplies StringRedisTemplate and LettuceConnectionFactory with timeout configs.
}
