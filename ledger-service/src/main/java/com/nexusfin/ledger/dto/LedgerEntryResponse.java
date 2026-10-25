package com.nexusfin.ledger.dto;

import com.nexusfin.ledger.domain.entity.LedgerEntry;
import com.nexusfin.ledger.domain.model.EntryType;
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
public class LedgerEntryResponse {

    private UUID id;
    private UUID transactionId;
    private UUID accountId;
    private String accountNumber;
    private EntryType entryType;
    private BigDecimal amount;
    private String currency;
    private BigDecimal balanceAfter;
    private Integer sequenceNumber;
    private Instant createdAt;

    public static LedgerEntryResponse fromEntity(LedgerEntry entry) {
        return LedgerEntryResponse.builder()
                .id(entry.getId())
                .transactionId(entry.getTransaction() != null ? entry.getTransaction().getId() : null)
                .accountId(entry.getAccount() != null ? entry.getAccount().getId() : null)
                .accountNumber(entry.getAccount() != null ? entry.getAccount().getAccountNumber() : null)
                .entryType(entry.getEntryType())
                .amount(entry.getAmount())
                .currency(entry.getCurrency())
                .balanceAfter(entry.getBalanceAfter())
                .sequenceNumber(entry.getSequenceNumber())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}
