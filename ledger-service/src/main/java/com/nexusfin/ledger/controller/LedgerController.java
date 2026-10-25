package com.nexusfin.ledger.controller;

import com.nexusfin.ledger.dto.PostTransactionRequest;
import com.nexusfin.ledger.dto.TransactionResponse;
import com.nexusfin.ledger.dto.TransferFundsRequest;
import com.nexusfin.ledger.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @PostMapping("/transactions")
    public ResponseEntity<TransactionResponse> postTransaction(@Valid @RequestBody PostTransactionRequest request) {
        TransactionResponse response = ledgerService.postTransaction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransactionResponse> transferFunds(@Valid @RequestBody TransferFundsRequest request) {
        TransactionResponse response = ledgerService.transferFunds(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/transactions/{reference}")
    public ResponseEntity<TransactionResponse> getTransactionByReference(@PathVariable String reference) {
        return ResponseEntity.ok(ledgerService.getTransactionByReference(reference));
    }
}
