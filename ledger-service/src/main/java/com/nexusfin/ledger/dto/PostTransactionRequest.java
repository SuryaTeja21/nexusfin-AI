package com.nexusfin.ledger.dto;

import com.nexusfin.ledger.domain.model.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostTransactionRequest {

    @NotBlank(message = "Transaction reference is required")
    @Size(max = 64, message = "Transaction reference cannot exceed 64 characters")
    private String transactionReference;

    @Size(max = 128, message = "Idempotency key cannot exceed 128 characters")
    private String idempotencyKey;

    @NotNull(message = "Transaction type is required")
    private TransactionType transactionType;

    @Size(max = 255, message = "Description cannot exceed 255 characters")
    private String description;

    @Valid
    private List<TransactionLegRequest> legs;

    @Valid
    private List<EntryRequest> entries;
}
