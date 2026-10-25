package com.nexusfin.ledger.exception;

public class DuplicateTransactionException extends LedgerException {

    public DuplicateTransactionException(String idempotencyKey) {
        super("Duplicate transaction detected with idempotency key: " + idempotencyKey);
    }
}
