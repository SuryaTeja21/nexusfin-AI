package com.nexusfin.fraud;

import com.nexusfin.fraud.domain.model.FraudDecision;
import com.nexusfin.fraud.domain.model.RiskLevel;
import com.nexusfin.fraud.dto.FraudEvaluationRequest;
import com.nexusfin.fraud.dto.FraudEvaluationResponse;
import com.nexusfin.fraud.service.AiFraudDetectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AiFraudServiceApplication.class)
@ActiveProfiles("test")
public class AiFraudDetectionServiceTest {

    @Autowired
    private AiFraudDetectionService fraudDetectionService;

    @Test
    @DisplayName("Normal Transaction Under $25k Should Be Automatically Approved with Low Risk Score")
    void shouldApproveLowRiskTransaction() {
        FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                .transactionReference("TX-SAFE-" + UUID.randomUUID())
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("150.0000"))
                .currency("USD")
                .description("Coffee shop payment")
                .clientIp("192.168.1.100")
                .build();

        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.getDecision()).isEqualTo(FraudDecision.APPROVED);
        assertThat(response.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(response.getRiskScore()).isLessThan(0.20);
    }

    @Test
    @DisplayName("Critical Transaction Over $1M Should Be Flagged and Blocked")
    void shouldRejectMillionDollarTransfer() {
        FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                .transactionReference("TX-SUSPICIOUS-" + UUID.randomUUID())
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("2500000.0000"))
                .currency("USD")
                .description("Offshore capital allocation")
                .clientIp("10.0.0.1")
                .build();

        FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.getDecision()).isEqualTo(FraudDecision.REJECTED);
        assertThat(response.getRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(response.getRiskScore()).isGreaterThan(0.90);
        assertThat(response.getRiskFactors()).contains("HIGH_VALUE_THRESHOLD_EXCEEDED");
    }
}
