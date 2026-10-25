package com.nexusfin.ledger.dto;

import com.nexusfin.ledger.domain.entity.Transaction;
import com.nexusfin.ledger.domain.model.TransactionStatus;
import com.nexusfin.ledger.domain.model.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private UUID id;
    private String transactionReference;
    private String idempotencyKey;
    private TransactionType transactionType;
    private TransactionStatus status;
    private String description;
    private Instant postedAt;
    private Instant createdAt;
    private List<LedgerEntryResponse> entries;

    public static TransactionResponse fromEntity(Transaction transaction) {
        List<LedgerEntryResponse> entryResponses = transaction.getEntries() != null
                ? transaction.getEntries().stream()
                .map(LedgerEntryResponse::fromEntity)
                .collect(Collectors.toList())
                : Collections.emptyList();

        return TransactionResponse.builder()
                .id(transaction.getId())
                .transactionReference(transaction.getTransactionReference())
                .idempotencyKey(transaction.getIdempotencyKey())
                .transactionType(transaction.getTransactionType())
                .status(transaction.getStatus())
                .description(transaction.getDescription())
                .postedAt(transaction.getPostedAt())
                .createdAt(transaction.getCreatedAt())
                .entries(entryResponses)
                .build();
    }
}
