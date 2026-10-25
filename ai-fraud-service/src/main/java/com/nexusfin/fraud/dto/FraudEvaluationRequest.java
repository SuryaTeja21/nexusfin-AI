package com.nexusfin.fraud.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudEvaluationRequest {

    @NotBlank(message = "Transaction reference is required")
    private String transactionReference;

    private UUID sourceAccountId;
    private UUID targetAccountId;
    private String accountNumber;

    @NotNull(message = "Transaction amount is required")
    @Positive(message = "Amount must be strictly positive")
    @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
    private BigDecimal amount;

    @NotBlank(message = "Currency code is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be an ISO 4217 3-letter code")
    private String currency;

    private String description;
    private String clientIp;
    private String merchantCategory;
    private String geolocation;
    private String deviceId;
    private Map<String, Object> metadata;
}
