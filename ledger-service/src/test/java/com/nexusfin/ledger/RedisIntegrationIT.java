package com.nexusfin.ledger;

import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.domain.model.EntryType;
import com.nexusfin.ledger.domain.model.TransactionType;
import com.nexusfin.ledger.dto.*;
import com.nexusfin.ledger.exception.ConcurrentTransactionException;
import com.nexusfin.ledger.service.AccountService;
import com.nexusfin.ledger.service.LedgerService;
import com.nexusfin.ledger.service.RedisCacheService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Phase 3: Redis In-Memory Accelerator & Idempotency Integration Tests")
public class RedisIntegrationIT extends AbstractPostgresIT {

    public static final GenericContainer<?> redis;

    static {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();
    }

    @DynamicPropertySource
    static void configureRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("nexusfin.redis.enabled", () -> "true");
        registry.add("nexusfin.redis.idempotency-ttl-seconds", () -> "86400");
        registry.add("nexusfin.redis.balance-ttl-seconds", () -> "300");
        registry.add("nexusfin.redis.in-flight-lock-ttl-seconds", () -> "60");
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("3.1 Redis is healthy and available in Testcontainers environment")
    void testRedisAvailability() {
        assertThat(redisCacheService.isAvailable()).isTrue();
        assertThat(redisTemplate).isNotNull();
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    @DisplayName("3.2 Idempotency cache hit & miss with Redis key persistence")
    void testIdempotencyCacheHitAndMiss() {
        String srcNum = "ACC-IDEM-SRC-" + UUID.randomUUID().toString().substring(0, 8);
        String dstNum = "ACC-IDEM-DST-" + UUID.randomUUID().toString().substring(0, 8);

        accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber(srcNum)
                .accountName("Source")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .initialBalance(new BigDecimal("5000.0000"))
                .build());

        accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber(dstNum)
                .accountName("Destination")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("1000.0000"))
                .build());

        String idempotencyKey = "KEY-IDEM-" + UUID.randomUUID();
        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-IDEM-" + UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .transactionType(TransactionType.TRANSFER)
                .description("Idempotency test")
                .legs(List.of(
                        TransactionLegRequest.builder().accountNumber(srcNum).entryType(EntryType.DEBIT).amount(new BigDecimal("100.0000")).currency("USD").build(),
                        TransactionLegRequest.builder().accountNumber(dstNum).entryType(EntryType.CREDIT).amount(new BigDecimal("100.0000")).currency("USD").build()
                ))
                .build();

        double missesBefore = meterRegistry.get("idempotency.cache.miss").counter().count();
        double hitsBefore = meterRegistry.get("idempotency.cache.hit").counter().count();

        // 1. First execution: Cache miss -> executes against DB -> stores in Redis
        TransactionResponse firstResponse = ledgerService.postTransaction(request);
        assertThat(firstResponse).isNotNull();
        assertThat(firstResponse.getId()).isNotNull();

        // Verify key exists in Redis with TTL
        String redisKey = "idem:" + idempotencyKey;
        assertThat(redisTemplate.hasKey(redisKey)).isTrue();
        Long ttl = redisTemplate.getExpire(redisKey);
        assertThat(ttl).isGreaterThan(0L);

        // 2. Second execution: Cache hit -> served directly from Redis
        TransactionResponse secondResponse = ledgerService.postTransaction(request);
        assertThat(secondResponse).isNotNull();
        assertThat(secondResponse.getId()).isEqualTo(firstResponse.getId());
        assertThat(secondResponse.getTransactionReference()).isEqualTo(firstResponse.getTransactionReference());

        double missesAfter = meterRegistry.get("idempotency.cache.miss").counter().count();
        double hitsAfter = meterRegistry.get("idempotency.cache.hit").counter().count();

        assertThat(missesAfter).isGreaterThan(missesBefore);
        assertThat(hitsAfter).isGreaterThan(hitsBefore);
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    @DisplayName("3.3 In-flight duplicate submission is blocked by distributed lock")
    void testInFlightDuplicateBlocked() {
        String srcNum = "ACC-LOCK-SRC-" + UUID.randomUUID().toString().substring(0, 8);
        String dstNum = "ACC-LOCK-DST-" + UUID.randomUUID().toString().substring(0, 8);

        accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber(srcNum)
                .accountName("Lock Source")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .initialBalance(new BigDecimal("2000.0000"))
                .build());

        accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber(dstNum)
                .accountName("Lock Destination")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("500.0000"))
                .build());

        String sharedKey = "KEY-IN-FLIGHT-" + UUID.randomUUID();

        // Manually acquire the distributed lock to simulate an active in-flight request
        boolean locked = redisCacheService.acquireInFlightLock(sharedKey);
        assertThat(locked).isTrue();

        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-IN-FLIGHT-" + UUID.randomUUID())
                .idempotencyKey(sharedKey)
                .transactionType(TransactionType.TRANSFER)
                .description("In-flight lock test")
                .legs(List.of(
                        TransactionLegRequest.builder().accountNumber(srcNum).entryType(EntryType.DEBIT).amount(new BigDecimal("50.0000")).currency("USD").build(),
                        TransactionLegRequest.builder().accountNumber(dstNum).entryType(EntryType.CREDIT).amount(new BigDecimal("50.0000")).currency("USD").build()
                ))
                .build();

        // Attempting to post the transaction while lock is held must throw ConcurrentTransactionException
        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(ConcurrentTransactionException.class)
                .hasMessageContaining(sharedKey);

        // Release the in-flight lock
        redisCacheService.releaseInFlightLock(sharedKey);

        // Now posting succeeds
        TransactionResponse response = ledgerService.postTransaction(request);
        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    @DisplayName("3.4 Account balance read-through cache with post-commit eviction ensures no stale reads")
    void testAccountBalanceReadThroughAndPostCommitEviction() {
        String accNum = "ACC-CACHE-" + UUID.randomUUID().toString().substring(0, 8);
        AccountResponse created = accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber(accNum)
                .accountName("Cache Test Account")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .initialBalance(new BigDecimal("1000.0000"))
                .build());

        // Account was cached on creation
        String cacheKey = "acc:balance:" + accNum;
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();

        double hitsBefore = meterRegistry.get("account.balance.cache.hit").counter().count();

        // Read account: served from cache
        AccountResponse cachedRead = accountService.getAccountByAccountNumber(accNum);
        assertThat(cachedRead.getBalance()).isEqualByComparingTo("1000.0000");

        double hitsAfter = meterRegistry.get("account.balance.cache.hit").counter().count();
        assertThat(hitsAfter).isGreaterThan(hitsBefore);

        // Destination account for transfer
        String destNum = "ACC-CACHE-DST-" + UUID.randomUUID().toString().substring(0, 8);
        accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber(destNum)
                .accountName("Cache Dest")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("500.0000"))
                .build());

        // Post transfer debiting accNum by 250.0000 (1000 -> 1250)
        ledgerService.postTransaction(PostTransactionRequest.builder()
                .transactionReference("TX-EVICT-" + UUID.randomUUID())
                .idempotencyKey("KEY-EVICT-" + UUID.randomUUID())
                .transactionType(TransactionType.TRANSFER)
                .description("Eviction test transfer")
                .legs(List.of(
                        TransactionLegRequest.builder().accountNumber(accNum).entryType(EntryType.DEBIT).amount(new BigDecimal("250.0000")).currency("USD").build(),
                        TransactionLegRequest.builder().accountNumber(destNum).entryType(EntryType.CREDIT).amount(new BigDecimal("250.0000")).currency("USD").build()
                ))
                .build());

        // Post-commit synchronization must have evicted the key from Redis
        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

        // Reading account fetches updated balance from PostgreSQL, NOT stale 1000.0000
        AccountResponse freshRead = accountService.getAccountByAccountNumber(accNum);
        assertThat(freshRead.getBalance()).isEqualByComparingTo("1250.0000");

        // Key is now re-cached with fresh balance
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
    }

    @Test
    @DisplayName("3.5 Redis fail-open guarantee: unconfigured/disabled Redis falls back cleanly without errors")
    void testFailOpenFallback() {
        RedisCacheService disconnectedService = new RedisCacheService(
                false, 86400, 300, 60, null, new com.fasterxml.jackson.databind.ObjectMapper(), meterRegistry);

        assertThat(disconnectedService.isAvailable()).isFalse();
        assertThat(disconnectedService.getIdempotentResponse("ANY-KEY")).isEmpty();
        assertThat(disconnectedService.acquireInFlightLock("ANY-KEY")).isTrue(); // fails open
        assertThat(disconnectedService.getCachedAccount("ANY-ACC")).isEmpty();

        // No exceptions thrown
        disconnectedService.storeIdempotentResponse("ANY-KEY", null);
        disconnectedService.releaseInFlightLock("ANY-KEY");
        disconnectedService.cacheAccount(null);
        disconnectedService.evictAccounts(List.of("ANY-ACC"));
    }
}
