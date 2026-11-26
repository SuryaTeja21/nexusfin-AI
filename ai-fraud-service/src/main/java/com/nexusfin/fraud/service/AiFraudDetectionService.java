package com.nexusfin.fraud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.fraud.domain.entity.AuditLogEntry;
import com.nexusfin.fraud.domain.model.FraudDecision;
import com.nexusfin.fraud.domain.model.RiskLevel;
import com.nexusfin.fraud.dto.FraudEvaluationRequest;
import com.nexusfin.fraud.dto.FraudEvaluationResponse;
import com.nexusfin.fraud.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LLM transaction risk-scoring service.
 * Integrates Spring AI / OpenAI with deterministic heuristic fallback.
 */
@Service
@RequiredArgsConstructor
public class AiFraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AiFraudDetectionService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ChatClient chatClient;

    @Value("${spring.ai.openai.api-key:dummy-key}")
    private String openAiApiKey;

    @Transactional
    public FraudEvaluationResponse evaluateTransaction(FraudEvaluationRequest request) {
        log.info("Evaluating transaction for fraud risk: ref={}, amount={} {}",
                request.getTransactionReference(), request.getAmount(), request.getCurrency());

        FraudEvaluationResponse response;

        // Use Spring AI ChatClient if active and configured with a non-placeholder API key
        if (chatClient != null && isConfiguredApiKey(openAiApiKey)) {
            try {
                response = evaluateWithSpringAi(request);
            } catch (Exception e) {
                log.warn("Spring AI inference or JSON parsing failed. Falling back to heuristic rule engine: {}", e.getMessage());
                response = evaluateWithHeuristics(request);
            }
        } else {
            log.debug("Spring AI ChatClient not configured with non-placeholder key. Using heuristic fallback engine.");
            response = evaluateWithHeuristics(request);
        }

        // Persist audit log record
        AuditLogEntry auditEntry = AuditLogEntry.builder()
                .id(UUID.randomUUID())
                .transactionReference(request.getTransactionReference())
                .sourceAccountId(request.getSourceAccountId())
                .targetAccountId(request.getTargetAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .riskScore(response.getRiskScore())
                .riskLevel(response.getRiskLevel())
                .decision(response.getDecision())
                .reasoning(response.getReasoning())
                .engine(response.getEngine())
                .modelName(response.getEvaluatedByModel())
                .createdAt(Instant.now())
                .build();

        auditLogRepository.save(auditEntry);
        return response;
    }

    private boolean isConfiguredApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String normalized = apiKey.trim().toLowerCase();
        return !normalized.contains("dummy")
                && !normalized.equals("demo-key")
                && !normalized.contains("your-openai-api-key");
    }

    private FraudEvaluationResponse evaluateWithSpringAi(FraudEvaluationRequest request) throws Exception {
        String systemPrompt = """
                You are the NexusFin AI Anti-Fraud and AML Intelligence Engine.
                Analyze the transaction and respond ONLY with a raw JSON object (no markdown, no extra text) adhering strictly to this schema:
                {
                  "riskScore": <number between 0.0 and 1.0>,
                  "riskLevel": "<LOW|MEDIUM|HIGH|CRITICAL>",
                  "decision": "<APPROVED|REVIEW_REQUIRED|REJECTED>",
                  "reason": "<concise justification>",
                  "riskFactors": ["<FACTOR_1>", ...]
                }
                Transaction Details:
                - Transaction Ref: %s
                - Amount: %s %s
                - Source Account ID: %s
                - Target Account ID: %s
                - Description: %s
                - Client IP: %s
                """.formatted(
                request.getTransactionReference(),
                request.getAmount(),
                request.getCurrency(),
                request.getSourceAccountId(),
                request.getTargetAccountId(),
                request.getDescription(),
                request.getClientIp()
        );

        String aiResponse = chatClient.call(new Prompt(systemPrompt)).getResult().getOutput().getContent();
        log.info("Spring AI Fraud Response received: {}", aiResponse);

        return parseLlmResponse(request, aiResponse);
    }

    private FraudEvaluationResponse parseLlmResponse(FraudEvaluationRequest request, String rawResponse) throws Exception {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalArgumentException("Empty response received from LLM");
        }

        String cleaned = rawResponse.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        JsonNode root = objectMapper.readTree(cleaned);

        if (!root.hasNonNull("riskScore")) {
            throw new IllegalArgumentException("LLM JSON missing required field: 'riskScore'");
        }
        double riskScore = root.get("riskScore").asDouble();
        if (riskScore < 0.0 || riskScore > 1.0) {
            throw new IllegalArgumentException("LLM riskScore out of bounds [0.0, 1.0]: " + riskScore);
        }

        if (!root.hasNonNull("riskLevel")) {
            throw new IllegalArgumentException("LLM JSON missing required field: 'riskLevel'");
        }
        RiskLevel riskLevel = RiskLevel.valueOf(root.get("riskLevel").asText().trim().toUpperCase());

        if (!root.hasNonNull("decision")) {
            throw new IllegalArgumentException("LLM JSON missing required field: 'decision'");
        }
        FraudDecision decision = FraudDecision.valueOf(root.get("decision").asText().trim().toUpperCase());

        String reason = "";
        if (root.hasNonNull("reason")) {
            reason = root.get("reason").asText().trim();
        } else if (root.hasNonNull("reasoning")) {
            reason = root.get("reasoning").asText().trim();
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("LLM JSON missing or empty 'reason' field");
        }

        List<String> riskFactors = new ArrayList<>();
        if (root.has("riskFactors") && root.get("riskFactors").isArray()) {
            for (JsonNode factorNode : root.get("riskFactors")) {
                riskFactors.add(factorNode.asText());
            }
        }

        return FraudEvaluationResponse.builder()
                .evaluationId(UUID.randomUUID())
                .transactionReference(request.getTransactionReference())
                .riskScore(riskScore)
                .riskLevel(riskLevel)
                .decision(decision)
                .reasoning(reason)
                .riskFactors(riskFactors)
                .evaluatedAt(Instant.now())
                .engine("LLM")
                .evaluatedByModel("Spring-AI-LLM")
                .build();
    }

    /**
     * Resilient deterministic rule-based fraud detection engine.
     */
    private FraudEvaluationResponse evaluateWithHeuristics(FraudEvaluationRequest request) {
        BigDecimal amount = request.getAmount();
        double riskScore;
        RiskLevel riskLevel;
        FraudDecision decision;
        List<String> riskFactors = new ArrayList<>();
        String reasoning;

        if (amount != null && amount.compareTo(new BigDecimal("1000000.0000")) > 0) {
            riskScore = 0.95;
            riskLevel = RiskLevel.CRITICAL;
            decision = FraudDecision.REJECTED;
            riskFactors.add("HIGH_VALUE_THRESHOLD_EXCEEDED");
            riskFactors.add("SUSPICIOUS_TRANSFER_SPIKE");
            reasoning = "Transaction exceeds statutory threshold of $1,000,000. Immediate AML review and block enforced.";
        } else if (amount != null && amount.compareTo(new BigDecimal("100000.0000")) > 0) {
            riskScore = 0.65;
            riskLevel = RiskLevel.HIGH;
            decision = FraudDecision.REVIEW_REQUIRED;
            riskFactors.add("LARGE_TRANSACTION_VALUE");
            reasoning = "Transaction amount is in excess of $100,000. Flagged for secondary human compliance review.";
        } else if (amount != null && amount.compareTo(new BigDecimal("25000.0000")) > 0) {
            riskScore = 0.35;
            riskLevel = RiskLevel.MEDIUM;
            decision = FraudDecision.APPROVED;
            riskFactors.add("MODERATE_VALUE_WATCHLIST");
            reasoning = "Standard moderate value transfer. Monitored under standard AML parameters.";
        } else {
            riskScore = 0.05;
            riskLevel = RiskLevel.LOW;
            decision = FraudDecision.APPROVED;
            reasoning = "Normal behavioral transaction patterns detected. Approved automatically.";
        }

        return FraudEvaluationResponse.builder()
                .evaluationId(UUID.randomUUID())
                .transactionReference(request.getTransactionReference())
                .riskScore(riskScore)
                .riskLevel(riskLevel)
                .decision(decision)
                .reasoning(reasoning)
                .riskFactors(riskFactors)
                .evaluatedAt(Instant.now())
                .engine("HEURISTIC")
                .evaluatedByModel("NexusFin-Heuristic-Rule-Engine-v1")
                .build();
    }
}
