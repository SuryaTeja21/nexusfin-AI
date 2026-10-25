package com.nexusfin.ledger.exception;

/**
 * Base domain exception for all NexusFin AI Ledger operations.
 */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }

    public LedgerException(String message, Throwable cause) {
        super(message, cause);
    }
}
