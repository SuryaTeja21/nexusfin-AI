package com.nexusfin.ledger.exception;

import com.nexusfin.ledger.domain.model.AccountStatus;

public class InvalidAccountStateException extends LedgerException {

    public InvalidAccountStateException(String accountNumber, AccountStatus status) {
        super(String.format("Account [%s] cannot participate in transaction due to inactive status [%s]",
                accountNumber, status));
    }
}
