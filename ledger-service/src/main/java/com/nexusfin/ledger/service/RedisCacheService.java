package com.nexusfin.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.ledger.dto.AccountResponse;
import com.nexusfin.ledger.dto.TransactionResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * In-memory caching and distributed synchronization accelerator powered by Redis.
 * <p>
 * Architectural Guarantees:
 * 1. Accelerator, NOT source of truth: PostgreSQL remains the single source of truth.
 * 2. Fail-Open: If Redis is unreachable, timed out, or disabled, all operations gracefully
 *    degrade to the database without throwing exceptions to callers.
 * 3. Observability: Emits Micrometer counters for cache hits and misses.
 */
@Slf4j
@Service
public class RedisCacheService {

    private static final String IDEM_PREFIX = "idem:";
    private static final String IDEM_LOCK_PREFIX = "idem:lock:";
    private static final String ACC_PREFIX = "acc:balance:";

    private final boolean enabled;
    private final long idempotencyTtlSeconds;
    private final long balanceTtlSeconds;
    private final long inFlightLockTtlSeconds;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final Counter idempotencyHitCounter;
    private final Counter idempotencyMissCounter;
    private final Counter balanceHitCounter;
    private final Counter balanceMissCounter;

    public RedisCacheService(
            @Value("${nexusfin.redis.enabled:true}") boolean enabled,
            @Value("${nexusfin.redis.idempotency-ttl-seconds:86400}") long idempotencyTtlSeconds,
            @Value("${nexusfin.redis.balance-ttl-seconds:300}") long balanceTtlSeconds,
            @Value("${nexusfin.redis.in-flight-lock-ttl-seconds:60}") long inFlightLockTtlSeconds,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.idempotencyTtlSeconds = idempotencyTtlSeconds;
        this.balanceTtlSeconds = balanceTtlSeconds;
        this.inFlightLockTtlSeconds = inFlightLockTtlSeconds;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

        this.idempotencyHitCounter = Counter.builder("idempotency.cache.hit")
                .description("Total number of idempotency cache hits served by Redis")
                .register(meterRegistry);
        this.idempotencyMissCounter = Counter.builder("idempotency.cache.miss")
                .description("Total number of idempotency cache misses falling back to PostgreSQL")
                .register(meterRegistry);
        this.balanceHitCounter = Counter.builder("account.balance.cache.hit")
                .description("Total number of account balance read cache hits served by Redis")
                .register(meterRegistry);
        this.balanceMissCounter = Counter.builder("account.balance.cache.miss")
                .description("Total number of account balance read cache misses falling back to PostgreSQL")
                .register(meterRegistry);
    }

    public boolean isAvailable() {
        if (!enabled || redisTemplate == null) {
            return false;
        }
        try {
            String ping = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(ping);
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Retrieves a cached TransactionResponse for an idempotency key.
     */
    public Optional<TransactionResponse> getIdempotentResponse(String idempotencyKey) {
        if (!enabled || redisTemplate == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        String key = IDEM_PREFIX + idempotencyKey.trim();
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                idempotencyHitCounter.increment();
                TransactionResponse response = objectMapper.readValue(json, TransactionResponse.class);
                return Optional.of(response);
            }
            idempotencyMissCounter.increment();
        } catch (Exception ex) {
            log.warn("Redis idempotency read failed for key [{}] (failing open to DB): {}", key, ex.getMessage());
            idempotencyMissCounter.increment();
        }
        return Optional.empty();
    }

    /**
     * Attempts to acquire an in-flight distributed lock for an idempotency key.
     * Returns false ONLY if the key is already present in Redis (in-flight duplicate detected).
     * If Redis is disabled or fails, returns true (fail-open so DB unique constraint handles concurrency).
     */
    public boolean acquireInFlightLock(String idempotencyKey) {
        if (!enabled || redisTemplate == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }

        String lockKey = IDEM_LOCK_PREFIX + idempotencyKey.trim();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, "PROCESSING", Duration.ofSeconds(inFlightLockTtlSeconds));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            log.warn("Redis in-flight lock acquisition failed for key [{}] (failing open to DB): {}", lockKey, ex.getMessage());
            return true;
        }
    }

    /**
     * Releases an in-flight distributed lock.
     */
    public void releaseInFlightLock(String idempotencyKey) {
        if (!enabled || redisTemplate == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        String lockKey = IDEM_LOCK_PREFIX + idempotencyKey.trim();
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception ex) {
            log.warn("Redis in-flight lock release failed for key [{}]: {}", lockKey, ex.getMessage());
        }
    }

    /**
     * Stores the committed TransactionResponse in the idempotency cache and releases the lock.
     */
    public void storeIdempotentResponse(String idempotencyKey, TransactionResponse response) {
        if (!enabled || redisTemplate == null || idempotencyKey == null || idempotencyKey.isBlank() || response == null) {
            return;
        }

        String key = IDEM_PREFIX + idempotencyKey.trim();
        String lockKey = IDEM_LOCK_PREFIX + idempotencyKey.trim();
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(idempotencyTtlSeconds));
            redisTemplate.delete(lockKey);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize TransactionResponse to JSON for key [{}]: {}", key, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Redis idempotency write failed for key [{}]: {}", key, ex.getMessage());
        }
    }

    /**
     * Reads a cached account balance / details response.
     */
    public Optional<AccountResponse> getCachedAccount(String accountNumber) {
        if (!enabled || redisTemplate == null || accountNumber == null || accountNumber.isBlank()) {
            return Optional.empty();
        }

        String key = ACC_PREFIX + accountNumber.trim();
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                balanceHitCounter.increment();
                AccountResponse response = objectMapper.readValue(json, AccountResponse.class);
                return Optional.of(response);
            }
            balanceMissCounter.increment();
        } catch (Exception ex) {
            log.warn("Redis account cache read failed for key [{}] (failing open to DB): {}", key, ex.getMessage());
            balanceMissCounter.increment();
        }
        return Optional.empty();
    }

    /**
     * Caches an AccountResponse in Redis with a short TTL.
     */
    public void cacheAccount(AccountResponse account) {
        if (!enabled || redisTemplate == null || account == null || account.getAccountNumber() == null) {
            return;
        }

        String key = ACC_PREFIX + account.getAccountNumber().trim();
        try {
            String json = objectMapper.writeValueAsString(account);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(balanceTtlSeconds));
        } catch (Exception ex) {
            log.warn("Redis account cache write failed for key [{}]: {}", key, ex.getMessage());
        }
    }

    /**
     * Evicts one or more accounts from Redis cache strictly after a transaction commits.
     */
    public void evictAccounts(Collection<String> accountNumbers) {
        if (!enabled || redisTemplate == null || accountNumbers == null || accountNumbers.isEmpty()) {
            return;
        }

        try {
            List<String> keys = accountNumbers.stream()
                    .filter(acc -> acc != null && !acc.isBlank())
                    .map(acc -> ACC_PREFIX + acc.trim())
                    .toList();
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ex) {
            log.warn("Redis account cache eviction failed: {}", ex.getMessage());
        }
    }
}
