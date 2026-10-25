package com.nexusfin.ledger.domain.model;

/**
 * Processing state of a financial transaction.
 */
public enum TransactionStatus {
    PENDING,
    POSTED,
    REJECTED,
    REVERSED
}
