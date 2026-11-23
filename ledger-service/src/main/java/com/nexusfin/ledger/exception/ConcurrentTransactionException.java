package com.nexusfin.ledger.exception;

/**
 * Exception thrown when a transaction submission with an identical idempotency key
 * is currently in-flight and being processed by another thread or request.
 */
public class ConcurrentTransactionException extends DuplicateTransactionException {

    public ConcurrentTransactionException(String idempotencyKey) {
        super("Transaction with idempotency key '" + idempotencyKey + "' is currently being processed by an in-flight request");
    }
}
