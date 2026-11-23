-- ==============================================================================
-- NexusFin AI - AI Fraud & AML Audit Intelligence Schema
-- Database: PostgreSQL 15+
-- Migration: V1__fraud_audit_log.sql
-- ==============================================================================

CREATE TABLE ai_fraud_audit_logs (
    id UUID PRIMARY KEY,
    transaction_reference VARCHAR(64) NOT NULL,
    source_account_id UUID NOT NULL,
    target_account_id UUID NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    risk_score DOUBLE PRECISION NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reasoning TEXT,
    engine VARCHAR(32),
    model_name VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_audit_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_audit_decision CHECK (decision IN ('APPROVED', 'REVIEW_REQUIRED', 'REJECTED')),
    CONSTRAINT chk_audit_engine CHECK (engine IS NULL OR engine IN ('LLM', 'HEURISTIC')),
    CONSTRAINT chk_audit_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_fraud_audit_tx_ref ON ai_fraud_audit_logs(transaction_reference);
CREATE INDEX idx_fraud_audit_created_at ON ai_fraud_audit_logs(created_at DESC);
CREATE INDEX idx_fraud_audit_decision ON ai_fraud_audit_logs(decision);
