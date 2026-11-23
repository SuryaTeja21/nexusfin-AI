package com.nexusfin.fraud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionPostedEvent {

    private UUID transactionId;
    private String transactionReference;
    private String idempotencyKey;
    private String transactionType;
    private BigDecimal amount;
    private BigDecimal totalVolume;
    private String currency;
    private String sourceAccountNumber;
    private String destinationAccountNumber;
    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private String description;
    private Instant postedAt;
    private java.util.List<java.util.Map<String, Object>> legs;

    public BigDecimal getEffectiveAmount() {
        if (amount != null) {
            return amount;
        }
        return totalVolume;
    }
}
