package com.nexusfin.ledger.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(String message) {
        super(message);
    }

    public InsufficientFundsException(String accountNumber, BigDecimal currentBalance, BigDecimal requestedAmount) {
        super(String.format("Insufficient funds in account [%s]: current balance [%s], requested [%s]",
                accountNumber, currentBalance, requestedAmount));
    }
}
