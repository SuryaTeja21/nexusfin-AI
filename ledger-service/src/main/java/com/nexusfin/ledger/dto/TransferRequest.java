package com.nexusfin.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequest {

    @NotNull(message = "Source account ID is required")
    private UUID sourceAccountId;

    @NotNull(message = "Target account ID is required")
    private UUID targetAccountId;

    @NotNull(message = "Transfer amount is required")
    @DecimalMin(value = "0.0001", message = "Transfer amount must be strictly positive")
    @Digits(integer = 15, fraction = 4, message = "Amount exceeds supported precision")
    private BigDecimal amount;

    @NotBlank(message = "Currency code is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a valid 3-letter ISO-4217 code")
    private String currency;

    @Size(max = 255, message = "Description cannot exceed 255 characters")
    private String description;

    @Size(max = 64, message = "Transaction reference cannot exceed 64 characters")
    private String transactionReference;

    @Size(max = 128, message = "Idempotency key cannot exceed 128 characters")
    private String idempotencyKey;
}
