package com.nexusfin.fraud;

import com.nexusfin.fraud.domain.entity.AuditLogEntry;
import com.nexusfin.fraud.domain.entity.ProcessedEvent;
import com.nexusfin.fraud.domain.model.FraudDecision;
import com.nexusfin.fraud.domain.model.RiskLevel;
import com.nexusfin.fraud.repository.AuditLogRepository;
import com.nexusfin.fraud.repository.ProcessedEventRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudFlywaySchemaIT {

    public static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("fraud_it_db")
                .withUsername("nexusfin")
                .withPassword("nexusfin_secret");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("nexusfin.kafka.consumer.enabled", () -> "false");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Phase 1.6 & 1.7: Flyway migrations V1 & V2 applied cleanly and Hibernate ddl-auto=validate succeeds")
    void flywayMigration_mustApplyCleanlyAndValidateEntityMapping() {
        // Assert Flyway migrations executed
        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("2");
        assertThat(current.getDescription()).isEqualTo("processed events");
        assertThat(current.getState().isApplied()).isTrue();
        assertThat(flyway.info().applied()).hasSize(2);

        // Assert entity insertion and retrieval matches schema exactly
        UUID entryId = UUID.randomUUID();
        AuditLogEntry entry = AuditLogEntry.builder()
                .id(entryId)
                .transactionReference("TX-FLYWAY-001")
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("125000.0000"))
                .currency("USD")
                .riskScore(0.65)
                .riskLevel(RiskLevel.HIGH)
                .decision(FraudDecision.REVIEW_REQUIRED)
                .reasoning("High value transfer requiring verification.")
                .engine("LLM")
                .modelName("Spring-AI-LLM")
                .createdAt(Instant.now())
                .build();

        auditLogRepository.save(entry);

        List<AuditLogEntry> saved = auditLogRepository.findByTransactionReferenceOrderByCreatedAtDesc("TX-FLYWAY-001");
        assertThat(saved).hasSize(1);
        AuditLogEntry retrieved = saved.get(0);
        assertThat(retrieved.getId()).isEqualTo(entryId);
        assertThat(retrieved.getEngine()).isEqualTo("LLM");
        assertThat(retrieved.getDecision()).isEqualTo(FraudDecision.REVIEW_REQUIRED);
        assertThat(retrieved.getAmount()).isEqualByComparingTo(new BigDecimal("125000.0000"));

        // Assert ProcessedEvent entity mapping and uniqueness constraint
        ProcessedEvent processedEvent = ProcessedEvent.builder()
                .eventId("TX-FLYWAY-001")
                .eventType("TRANSACTION_POSTED")
                .processedAt(Instant.now())
                .build();
        processedEventRepository.save(processedEvent);
        assertThat(processedEventRepository.existsById("TX-FLYWAY-001")).isTrue();

        assertThatThrownBy(() -> {
            jdbcTemplate.update(
                    "INSERT INTO processed_events (event_id, event_type, processed_at) VALUES (?, ?, ?)",
                    "TX-FLYWAY-001", "TRANSACTION_POSTED", java.sql.Timestamp.from(Instant.now()));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
