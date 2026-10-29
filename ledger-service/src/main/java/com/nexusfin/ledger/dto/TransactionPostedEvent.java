package com.nexusfin.ledger.dto;

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
public class TransactionPostedEvent {

    private UUID transactionId;
    private String transactionReference;
    private String idempotencyKey;
    private String transactionType;
    private BigDecimal amount;
    private String currency;
    private String sourceAccountNumber;
    private String destinationAccountNumber;
    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private String description;
    private Instant postedAt;
}
