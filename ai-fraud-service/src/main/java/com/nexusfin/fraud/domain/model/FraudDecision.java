package com.nexusfin.fraud.domain.model;

/**
 * Automated compliance & security decision for a financial transaction.
 */
public enum FraudDecision {
    APPROVED,
    REVIEW_REQUIRED,
    REJECTED
}
