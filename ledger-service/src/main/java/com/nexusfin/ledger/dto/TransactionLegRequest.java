package com.nexusfin.ledger.dto;

import com.nexusfin.ledger.domain.model.EntryType;
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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionLegRequest {

    @NotBlank(message = "Account number is required for transaction leg")
    private String accountNumber;

    @NotNull(message = "Entry type is required (DEBIT or CREDIT)")
    private EntryType entryType;

    @NotNull(message = "Leg amount is required")
    @Positive(message = "Leg amount must be strictly positive")
    @DecimalMin(value = "0.0001", message = "Leg amount must be at least 0.0001")
    private BigDecimal amount;

    @NotBlank(message = "Currency code is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be an ISO 4217 3-letter code")
    private String currency;
}
