-- ==============================================================================
-- NexusFin AI - Enterprise Core ACID Double-Entry Financial Ledger Schema
-- Database: PostgreSQL 15+
-- Migration: V1__init_ledger_schema.sql
-- ==============================================================================

-- 1. Accounts Table: Core chart of accounts with strict currency & balance precision
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    account_number VARCHAR(64) NOT NULL UNIQUE,
    account_name VARCHAR(128) NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_account_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_accounts_account_number ON accounts(account_number);
CREATE INDEX idx_accounts_type ON accounts(account_type);
CREATE INDEX idx_accounts_currency ON accounts(currency);
CREATE INDEX idx_accounts_status ON accounts(status);

-- 2. Transactions Table: Immutable journal header with idempotency key
CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    transaction_reference VARCHAR(64) NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) UNIQUE,
    description VARCHAR(255),
    transaction_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'POSTED',
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_transaction_type CHECK (transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'FEE', 'SETTLEMENT', 'ADJUSTMENT')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('PENDING', 'POSTED', 'REJECTED', 'REVERSED'))
);

CREATE INDEX idx_transactions_reference ON transactions(transaction_reference);
CREATE INDEX idx_transactions_idempotency ON transactions(idempotency_key);
CREATE INDEX idx_transactions_posted_at ON transactions(posted_at DESC);

-- 3. Ledger Entries Table: Immutable double-entry legs (Debits & Credits)
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    entry_type VARCHAR(16) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    sequence_number INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_entry_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_entry_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_transaction_sequence UNIQUE (transaction_id, sequence_number)
);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries(account_id, created_at DESC);
