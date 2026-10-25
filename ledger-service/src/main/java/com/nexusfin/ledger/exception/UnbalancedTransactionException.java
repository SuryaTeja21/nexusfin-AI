package com.nexusfin.ledger.exception;

import java.math.BigDecimal;

public class UnbalancedTransactionException extends DoubleEntryImbalanceException {

    public UnbalancedTransactionException(BigDecimal totalDebits, BigDecimal totalCredits) {
        super(totalDebits, totalCredits);
    }
}
