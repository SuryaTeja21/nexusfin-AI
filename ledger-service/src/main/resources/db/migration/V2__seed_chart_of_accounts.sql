-- ==============================================================================
-- NexusFin AI - Seed Initial Chart of Accounts (COA)
-- Database: PostgreSQL 15+
-- Migration: V2__seed_chart_of_accounts.sql
-- ==============================================================================

-- 1. Central Reserve (Asset Account)
INSERT INTO accounts (id, account_number, account_name, account_type, currency, balance, status, created_at, updated_at, version)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'ACC-ASSET-CENTRAL-RESERVE-USD',
    'NexusFin Central Treasury Reserve',
    'ASSET',
    'USD',
    10000000.0000,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
);

-- 2. Customer Deposit Clearing (Liability Account)
INSERT INTO accounts (id, account_number, account_name, account_type, currency, balance, status, created_at, updated_at, version)
VALUES (
    'a0000000-0000-0000-0000-000000000002',
    'ACC-LIAB-CUSTOMER-CLEARING-USD',
    'Customer Deposit Clearing Ledger',
    'LIABILITY',
    'USD',
    10000000.0000,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
);

-- 3. Platform Fee Revenue (Revenue Account)
INSERT INTO accounts (id, account_number, account_name, account_type, currency, balance, status, created_at, updated_at, version)
VALUES (
    'a0000000-0000-0000-0000-000000000003',
    'ACC-REV-PLATFORM-FEES-USD',
    'Platform Transaction Fee Income',
    'REVENUE',
    'USD',
    0.0000,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
);

-- 4. Operational Expense (Expense Account)
INSERT INTO accounts (id, account_number, account_name, account_type, currency, balance, status, created_at, updated_at, version)
VALUES (
    'a0000000-0000-0000-0000-000000000004',
    'ACC-EXP-PROCESSING-FEES-USD',
    'Interchange and Network Fees Expense',
    'EXPENSE',
    'USD',
    0.0000,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
);
