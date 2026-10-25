package com.nexusfin.ledger.dto;

import com.nexusfin.ledger.domain.entity.Account;
import com.nexusfin.ledger.domain.model.AccountStatus;
import com.nexusfin.ledger.domain.model.AccountType;
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
public class AccountResponse {

    private UUID id;
    private String accountNumber;
    private String accountName;
    private AccountType accountType;
    private String currency;
    private BigDecimal balance;
    private AccountStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public static AccountResponse fromEntity(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .accountName(account.getAccountName())
                .accountType(account.getAccountType())
                .currency(account.getCurrency())
                .balance(account.getBalance())
                .status(account.getStatus())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .version(account.getVersion())
                .build();
    }
}
