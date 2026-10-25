package com.nexusfin.ledger.exception;

import java.util.UUID;

public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(UUID accountId) {
        super("Account not found with ID: " + accountId);
    }

    public AccountNotFoundException(String accountNumber) {
        super("Account not found with account number: " + accountNumber);
    }
}
