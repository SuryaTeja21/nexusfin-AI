package com.nexusfin.ledger.dto;

import com.nexusfin.ledger.domain.model.EntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
public class EntryRequest {

    @NotNull(message = "Account ID is mandatory")
    private UUID accountId;

    @NotNull(message = "Entry type must be DEBIT or CREDIT")
    private EntryType entryType;

    @NotNull(message = "Amount is mandatory")
    @DecimalMin(value = "0.0001", message = "Amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "Amount exceeds supported precision (max 15 integer digits, 4 decimal places)")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a valid 3-letter ISO-4217 code")
    private String currency;
}
