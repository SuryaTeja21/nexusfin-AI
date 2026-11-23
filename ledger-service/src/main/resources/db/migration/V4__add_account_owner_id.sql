-- ==============================================================================
-- NexusFin AI - Add Account Ownership & Multi-Tenant Access Control
-- Database: PostgreSQL 15+
-- Migration: V4__add_account_owner_id.sql
-- ==============================================================================

ALTER TABLE accounts ADD COLUMN owner_id VARCHAR(64);
CREATE INDEX idx_accounts_owner_id ON accounts(owner_id);

-- Seed demo customer accounts owned by customer1 (c0000000-0000-0000-0000-000000000001)
INSERT INTO accounts (id, account_number, account_name, account_type, currency, balance, status, owner_id, created_at, updated_at, version)
VALUES (
    'c0000000-0000-0000-0000-000000000101',
    'ACC-CUSTOMER-ALICE-USD',
    'Alice Primary Checking Account',
    'LIABILITY',
    'USD',
    25000.0000,
    'ACTIVE',
    'c0000000-0000-0000-0000-000000000001',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
);

INSERT INTO accounts (id, account_number, account_name, account_type, currency, balance, status, owner_id, created_at, updated_at, version)
VALUES (
    'c0000000-0000-0000-0000-000000000102',
    'ACC-CUSTOMER-ALICE-SAVINGS-USD',
    'Alice High-Yield Savings Account',
    'LIABILITY',
    'USD',
    50000.0000,
    'ACTIVE',
    'c0000000-0000-0000-0000-000000000001',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
);

-- Seed demo customer accounts owned by customer2 (c0000000-0000-0000-0000-000000000002) for authorization testing
INSERT INTO accounts (id, account_number, account_name, account_type, currency, balance, status, owner_id, created_at, updated_at, version)
VALUES (
    'c0000000-0000-0000-0000-000000000103',
    'ACC-CUSTOMER-BOB-USD',
    'Bob Checking Account',
    'LIABILITY',
    'USD',
    10000.0000,
    'ACTIVE',
    'c0000000-0000-0000-0000-000000000002',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
);
