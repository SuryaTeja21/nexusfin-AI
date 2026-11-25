-- ==============================================================================
-- NexusFin AI - Distributed Trace & Correlation Context for Outbox Events
-- Migration: V5__add_outbox_trace_context.sql
-- ==============================================================================

ALTER TABLE outbox_events
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN trace_parent VARCHAR(256);
