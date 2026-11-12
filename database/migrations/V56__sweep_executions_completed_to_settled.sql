-- ============================================================================
-- V56 - Seeded sweep executions with status 'COMPLETED' -> 'SETTLED' (external-bank sweeps R21).
--
-- SweepExecution.ExecutionStatus has no COMPLETED (COMMITTED, SETTLED, SUCCESS, FAILED, PARTIAL, PENDING, SKIPPED), but
-- the seed (global_mncs_pooling.sql, in the dump) wrote six with it: the oldest execution of SWP-ALBN-001, -BRTO-001,
-- -HEL-001, -MAWR-001 and two of -MERC-001. Loading such a row fails the enum mapping, so
-- GET /api/v1/sweeping/rules/{id}/history, sorted oldest first, returned 500 for those rules; /history (newest first)
-- only failed on the page that reached them. They are finished sweeps with completed_at set: SETTLED. Idempotent.
-- ============================================================================
UPDATE sweep_executions SET status = 'SETTLED' WHERE status = 'COMPLETED';
