-- ==============================================================================
-- NexusFin AI - Idempotent Consumer Deduplication Schema
-- Database: PostgreSQL 15+
-- Migration: V2__processed_events.sql
-- ==============================================================================

CREATE TABLE processed_events (
    event_id VARCHAR(128) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at DESC);
