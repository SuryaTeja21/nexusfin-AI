package com.nexusfin.ledger.domain.model;

import java.math.BigDecimal;

/**
 * Standard 5-category Chart of Accounts classification with formal double-entry normal balances.
 * <p>
 * Fundamental Accounting Equation:
 * Assets = Liabilities + Equity + (Revenue - Expenses)
 */
public enum AccountType {
    ASSET(EntryType.DEBIT),
    LIABILITY(EntryType.CREDIT),
    EQUITY(EntryType.CREDIT),
    REVENUE(EntryType.CREDIT),
    EXPENSE(EntryType.DEBIT);

    private final EntryType normalBalance;

    AccountType(EntryType normalBalance) {
        this.normalBalance = normalBalance;
    }

    public EntryType getNormalBalance() {
        return normalBalance;
    }

    /**
     * Calculates the new balance for the account according to standard accounting conventions:
     * - Accounts with DEBIT normal balance (ASSET, EXPENSE) increase with DEBIT and decrease with CREDIT.
     * - Accounts with CREDIT normal balance (LIABILITY, EQUITY, REVENUE) increase with CREDIT and decrease with DEBIT.
     *
     * @param currentBalance Current balance of the account
     * @param entryType      DEBIT or CREDIT
     * @param amount         Positive monetary amount
     * @return Updated balance
     */
    public BigDecimal calculateNewBalance(BigDecimal currentBalance, EntryType entryType, BigDecimal amount) {
        if (this.normalBalance == entryType) {
            return currentBalance.add(amount);
        } else {
            return currentBalance.subtract(amount);
        }
    }
}
