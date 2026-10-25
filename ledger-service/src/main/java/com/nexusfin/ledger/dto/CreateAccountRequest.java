package com.nexusfin.ledger.dto;

import com.nexusfin.ledger.domain.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {

    @NotBlank(message = "Account number is required")
    @Size(max = 64, message = "Account number cannot exceed 64 characters")
    private String accountNumber;

    @NotBlank(message = "Account name is required")
    @Size(max = 128, message = "Account name cannot exceed 128 characters")
    private String accountName;

    @NotNull(message = "Account type is required (ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE)")
    private AccountType accountType;

    @NotBlank(message = "Currency code is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be an ISO 4217 3-letter code (e.g. USD, EUR, GBP)")
    private String currency;

    private BigDecimal initialBalance;
}
