package com.nexusfin.fraud.service;

import com.nexusfin.fraud.domain.entity.AuditLogEntry;
import com.nexusfin.fraud.domain.model.FraudDecision;
import com.nexusfin.fraud.domain.model.RiskLevel;
import com.nexusfin.fraud.dto.FraudEvaluationRequest;
import com.nexusfin.fraud.dto.FraudEvaluationResponse;
import com.nexusfin.fraud.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiFraudDetectionServiceUnitTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ChatClient chatClient;

    @InjectMocks
    private AiFraudDetectionService fraudDetectionService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fraudDetectionService, "chatClient", chatClient);
        ReflectionTestUtils.setField(fraudDetectionService, "openAiApiKey", "sk-live-valid-test-key");
    }

    @Test
    @DisplayName("AI Flow: ChatClient returns assessment, audits and completes evaluation")
    void shouldEvaluateWithSpringAiWhenConfigured() {
        ChatResponse mockChatResponse = new ChatResponse(List.of(
                new Generation("AI risk analysis: low risk retail transaction.")
        ));
        when(chatClient.call(any(Prompt.class))).thenReturn(mockChatResponse);

        FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                .transactionReference("TX-AI-001")
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("150.0000"))
                .currency("USD")
                .description("Retail purchase")
                .clientIp("127.0.0.1")
                .build();

        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.getDecision()).isEqualTo(FraudDecision.APPROVED);
        assertThat(response.getRiskLevel()).isEqualTo(RiskLevel.LOW);

        // Verify ChatClient was called with prompt
        verify(chatClient, times(1)).call(any(Prompt.class));

        // Verify audit log entry persisted
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getTransactionReference()).isEqualTo("TX-AI-001");
        assertThat(entry.getDecision()).isEqualTo(FraudDecision.APPROVED);
    }

    @Test
    @DisplayName("Resilience: Falls back gracefully to heuristic engine when ChatClient fails")
    void shouldFallbackToHeuristicsWhenChatClientThrowsException() {
        when(chatClient.call(any(Prompt.class))).thenThrow(new RuntimeException("OpenAI API rate limit / 503"));

        FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                .transactionReference("TX-FALLBACK-001")
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("250000.0000"))
                .currency("USD")
                .description("Commercial property transfer")
                .clientIp("127.0.0.1")
                .build();

        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.getDecision()).isEqualTo(FraudDecision.REVIEW_REQUIRED);
        assertThat(response.getRiskLevel()).isEqualTo(RiskLevel.HIGH);

        // Verify audit log was still recorded despite AI exception
        verify(auditLogRepository, times(1)).save(any(AuditLogEntry.class));
    }

    @Test
    @DisplayName("Heuristics: High value transactions exceeding $1,000,000 must be rejected as CRITICAL")
    void shouldRejectMillionDollarTransaction() {
        ReflectionTestUtils.setField(fraudDetectionService, "openAiApiKey", "dummy-key");

        FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                .transactionReference("TX-CRITICAL-001")
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("1500000.0000"))
                .currency("USD")
                .description("Offshore capital allocation")
                .clientIp("10.0.0.1")
                .build();

        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.getDecision()).isEqualTo(FraudDecision.REJECTED);
        assertThat(response.getRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(response.getRiskScore()).isGreaterThanOrEqualTo(0.95);
        assertThat(response.getRiskFactors()).contains("HIGH_VALUE_THRESHOLD_EXCEEDED");

        verify(auditLogRepository, times(1)).save(any(AuditLogEntry.class));
    }
}
