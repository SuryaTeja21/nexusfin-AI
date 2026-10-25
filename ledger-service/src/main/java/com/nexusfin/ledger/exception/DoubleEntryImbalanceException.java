package com.nexusfin.ledger.exception;

import java.math.BigDecimal;

/**
 * Thrown when the fundamental accounting invariant is violated:
 * Total Debits != Total Credits.
 */
public class DoubleEntryImbalanceException extends LedgerException {
    private final BigDecimal totalDebits;
    private final BigDecimal totalCredits;

    public DoubleEntryImbalanceException(BigDecimal totalDebits, BigDecimal totalCredits) {
        super(String.format("Double-entry transaction imbalance detected. Total Debits: %s, Total Credits: %s (Difference: %s)",
                totalDebits, totalCredits, totalDebits.subtract(totalCredits)));
        this.totalDebits = totalDebits;
        this.totalCredits = totalCredits;
    }

    public BigDecimal getTotalDebits() {
        return totalDebits;
    }

    public BigDecimal getTotalCredits() {
        return totalCredits;
    }
}
