package com.nexusfin.fraud.dto;

import com.nexusfin.fraud.domain.model.FraudDecision;
import com.nexusfin.fraud.domain.model.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudEvaluationResponse {

    private UUID evaluationId;
    private String transactionReference;
    private double riskScore; // 0.0 to 1.0
    private RiskLevel riskLevel;
    private FraudDecision decision;
    private String reasoning;
    private List<String> riskFactors;
    private Instant evaluatedAt;
    private String evaluatedByModel;
}
