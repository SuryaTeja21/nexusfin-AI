package com.nexusfin.fraud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.Spy;
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

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AiFraudDetectionService fraudDetectionService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fraudDetectionService, "chatClient", chatClient);
        ReflectionTestUtils.setField(fraudDetectionService, "openAiApiKey", "sk-live-valid-test-key");
    }

    @Test
    @DisplayName("Option A: ChatClient returns valid JSON -> parsed with validation and engine = LLM")
    void shouldEvaluateAndParseStrictJsonFromLlm() {
        String validJson = """
                {
                  "riskScore": 0.85,
                  "riskLevel": "HIGH",
                  "decision": "REVIEW_REQUIRED",
                  "reason": "Rapid transfer velocity detected on target account.",
                  "riskFactors": ["HIGH_VELOCITY", "SUSPICIOUS_TIMING"]
                }
                """;
        ChatResponse mockChatResponse = new ChatResponse(List.of(new Generation(validJson)));
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
        assertThat(response.getDecision()).isEqualTo(FraudDecision.REVIEW_REQUIRED);
        assertThat(response.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(response.getRiskScore()).isEqualTo(0.85);
        assertThat(response.getReasoning()).isEqualTo("Rapid transfer velocity detected on target account.");
        assertThat(response.getRiskFactors()).containsExactly("HIGH_VELOCITY", "SUSPICIOUS_TIMING");
        assertThat(response.getEngine()).isEqualTo("LLM");
        assertThat(response.getEvaluatedByModel()).isEqualTo("Spring-AI-LLM");

        // Verify audit log entry persisted with engine = LLM
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getTransactionReference()).isEqualTo("TX-AI-001");
        assertThat(entry.getDecision()).isEqualTo(FraudDecision.REVIEW_REQUIRED);
        assertThat(entry.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(entry.getRiskScore()).isEqualTo(0.85);
        assertThat(entry.getEngine()).isEqualTo("LLM");
        assertThat(entry.getModelName()).isEqualTo("Spring-AI-LLM");
    }

    @Test
    @DisplayName("Option A: ChatClient returns markdown code block JSON -> stripped, parsed, engine = LLM")
    void shouldParseMarkdownFencedJsonFromLlm() {
        String fencedJson = """
                ```json
                {
                  "riskScore": 0.10,
                  "riskLevel": "LOW",
                  "decision": "APPROVED",
                  "reason": "Standard retail transaction.",
                  "riskFactors": []
                }
                ```
                """;
        ChatResponse mockChatResponse = new ChatResponse(List.of(new Generation(fencedJson)));
        when(chatClient.call(any(Prompt.class))).thenReturn(mockChatResponse);

        FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                .transactionReference("TX-AI-FENCE")
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("50.0000"))
                .currency("USD")
                .description("Coffee")
                .clientIp("127.0.0.1")
                .build();

        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.getDecision()).isEqualTo(FraudDecision.APPROVED);
        assertThat(response.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(response.getRiskScore()).isEqualTo(0.10);
        assertThat(response.getEngine()).isEqualTo("LLM");
    }

    @Test
    @DisplayName("Option A: ChatClient returns malformed non-JSON -> falls back to heuristic engine, engine = HEURISTIC")
    void shouldFallbackToHeuristicsWhenLlmReturnsMalformedJson() {
        String malformed = "I evaluated this transaction and believe it is somewhat risky.";
        ChatResponse mockChatResponse = new ChatResponse(List.of(new Generation(malformed)));
        when(chatClient.call(any(Prompt.class))).thenReturn(mockChatResponse);

        FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                .transactionReference("TX-FALLBACK-MALFORMED")
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("150000.0000"))
                .currency("USD")
                .description("Commercial property transfer")
                .clientIp("127.0.0.1")
                .build();

        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

        assertThat(response).isNotNull();
        // $150,000 in heuristics yields HIGH / REVIEW_REQUIRED
        assertThat(response.getDecision()).isEqualTo(FraudDecision.REVIEW_REQUIRED);
        assertThat(response.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(response.getEngine()).isEqualTo("HEURISTIC");
        assertThat(response.getEvaluatedByModel()).isEqualTo("NexusFin-Heuristic-Rule-Engine-v1");

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getEngine()).isEqualTo("HEURISTIC");
    }

    @Test
    @DisplayName("Option A: ChatClient returns invalid out-of-bounds riskScore -> falls back to heuristics")
    void shouldFallbackToHeuristicsWhenRiskScoreOutOfBounds() {
        String invalidScoreJson = """
                {
                  "riskScore": 2.50,
                  "riskLevel": "CRITICAL",
                  "decision": "REJECTED",
                  "reason": "Impossible score"
                }
                """;
        ChatResponse mockChatResponse = new ChatResponse(List.of(new Generation(invalidScoreJson)));
        when(chatClient.call(any(Prompt.class))).thenReturn(mockChatResponse);

        FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                .transactionReference("TX-INVALID-SCORE")
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("100.0000"))
                .currency("USD")
                .description("Small transfer")
                .clientIp("127.0.0.1")
                .build();

        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

        assertThat(response.getEngine()).isEqualTo("HEURISTIC");
        assertThat(response.getEvaluatedByModel()).isEqualTo("NexusFin-Heuristic-Rule-Engine-v1");
    }

    @Test
    @DisplayName("Option A: ChatClient throws timeout / rate limit exception -> falls back to heuristics")
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
        assertThat(response.getEngine()).isEqualTo("HEURISTIC");

        verify(auditLogRepository, times(1)).save(any(AuditLogEntry.class));
    }

    @Test
    @DisplayName("Heuristics: High value transactions exceeding $1,000,000 must be rejected as CRITICAL")
    void shouldRejectMillionDollarTransactionUnderHeuristics() {
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
        assertThat(response.getEngine()).isEqualTo("HEURISTIC");

        verify(auditLogRepository, times(1)).save(any(AuditLogEntry.class));
    }
}
