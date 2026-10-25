package com.nexusfin.ledger.exception;

public class CurrencyMismatchException extends LedgerException {

    public CurrencyMismatchException(String message) {
        super(message);
    }

    public CurrencyMismatchException(String expectedCurrency, String actualCurrency, String accountNumber) {
        super(String.format("Currency mismatch for account [%s]: expected [%s] but received [%s]",
                accountNumber, expectedCurrency, actualCurrency));
    }
}
