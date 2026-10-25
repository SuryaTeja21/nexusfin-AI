package com.nexusfin.ledger.exception;

/**
 * Thrown when attempting to operate on a FROZEN or CLOSED account.
 */
public class AccountFrozenException extends LedgerException {
    public AccountFrozenException(String message) {
        super(message);
    }
}
