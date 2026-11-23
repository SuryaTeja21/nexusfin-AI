package com.nexusfin.ledger.controller;

import com.nexusfin.ledger.dto.PostTransactionRequest;
import com.nexusfin.ledger.dto.TransactionResponse;
import com.nexusfin.ledger.dto.TransferFundsRequest;
import com.nexusfin.ledger.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @PostMapping("/transactions")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<TransactionResponse> postTransaction(@Valid @RequestBody PostTransactionRequest request) {
        TransactionResponse response = ledgerService.postTransaction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<TransactionResponse> transferFunds(@Valid @RequestBody TransferFundsRequest request) {
        TransactionResponse response = ledgerService.transferFunds(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/transactions/{reference}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<TransactionResponse> getTransactionByReference(@PathVariable String reference) {
        return ResponseEntity.ok(ledgerService.getTransactionByReference(reference));
    }
}
