package com.nexusfin.ledger.domain.model;

/**
 * Standard financial transaction types supported by the NexusFin AI ledger.
 */
public enum TransactionType {
    TRANSFER,
    DEPOSIT,
    WITHDRAWAL,
    FEE,
    SETTLEMENT,
    ADJUSTMENT
}
