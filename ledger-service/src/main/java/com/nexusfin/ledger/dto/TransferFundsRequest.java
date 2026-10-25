package com.nexusfin.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * High-level convenient transfer request that translates automatically into balanced double-entry legs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferFundsRequest {

    @NotBlank(message = "Source account number is required")
    private String sourceAccountNumber;

    @NotBlank(message = "Destination account number is required")
    private String destinationAccountNumber;

    @NotNull(message = "Transfer amount is required")
    @Positive(message = "Transfer amount must be strictly positive")
    @DecimalMin(value = "0.0001", message = "Transfer amount must be at least 0.0001")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be an ISO 4217 3-letter code")
    private String currency;

    @NotBlank(message = "Idempotency key is required")
    @Size(max = 128, message = "Idempotency key cannot exceed 128 characters")
    private String idempotencyKey;

    @Size(max = 64, message = "Transaction reference cannot exceed 64 characters")
    private String transactionReference;

    @Size(max = 255, message = "Description cannot exceed 255 characters")
    private String description;
}
