-- ============================================================================
-- Simulator Phase 4 — shadow_sync_log seed (demo data, Option B)
-- ============================================================================
-- The seed ships every physical account as CORE_BANKING (no external feeds),
-- so the Source-quality line would be uniformly zero. To demonstrate real
-- value (Option-B choice), this:
--   (1) assigns realistic external feeds to non-home/external banks, and
--   (2) generates ~90 days of sync history for those accounts.
--
-- IDEMPOTENT + NON-DESTRUCTIVE:
--   (1) only flips rows still at the default 'CORE_BANKING' (never clobbers
--       a real/curated value); home + GCC-regional banks stay CORE_BANKING.
--   (2) skips any account that already has shadow_sync_log rows.
-- Safe to re-run.
-- ============================================================================

-- (1) Realistic external data sources by bank. NB: values MUST be valid
--     `PhysicalAccount.DataSource` enum constants (CORE_BANKING, SWIFT_MT940,
--     SWIFT_MT942, OPEN_BANKING_PSD2/UK/UAE/KSA) — there is no bare
--     'OPEN_BANKING'. Emirates NBD / Mashreq / Saudi stay CORE_BANKING.
UPDATE physical_accounts SET data_source = 'SWIFT_MT940'
WHERE data_source = 'CORE_BANKING'
  AND bank_code IN ('HBMEAEADXXX','HBUKGB4BXXX','HSBC','SCBLSGSGXXX');

UPDATE physical_accounts SET data_source = 'SWIFT_MT942'
WHERE data_source = 'CORE_BANKING'
  AND bank_code IN ('CITIUS33XXX','CHASUS33XXX','BOFAUS3NXXX');

UPDATE physical_accounts SET data_source = 'OPEN_BANKING_UK'
WHERE data_source = 'CORE_BANKING'
  AND bank_code IN ('BARCGB22XXX','LOYDGB2LXXX');

UPDATE physical_accounts SET data_source = 'OPEN_BANKING_PSD2'
WHERE data_source = 'CORE_BANKING'
  AND bank_code IN ('BNPAFRPPXXX','COBADEFFXXX','DEUTDEFFXXX','UNCRITMMXXX');

-- (2) ~90 daily expected syncs per external-feed account; per-source
--     miss-rate + lag profile (MT940 ~1.5%/360m, MT942 ~1.0%/240m,
--     OPEN_BANKING ~0.5%/60m). Anything not SUCCESS counts toward miss_rate.
INSERT INTO shadow_sync_log
    (physical_account_id, data_source, expected_sync_at,
     actual_sync_at, lag_minutes, sync_status)
SELECT pa.id,
       pa.data_source,
       d.expected,
       CASE WHEN d.missed THEN NULL
            ELSE d.expected + make_interval(mins => d.lag) END,
       CASE WHEN d.missed THEN NULL ELSE d.lag END,
       CASE WHEN d.missed THEN 'MISSED' ELSE 'SUCCESS' END
FROM physical_accounts pa
CROSS JOIN LATERAL (
    SELECT gs AS expected,
           (random() < CASE pa.data_source
                            WHEN 'SWIFT_MT940' THEN 0.015
                            WHEN 'SWIFT_MT942' THEN 0.010
                            ELSE 0.005 END) AS missed,
           GREATEST(1, (CASE pa.data_source
                             WHEN 'SWIFT_MT940' THEN 360
                             WHEN 'SWIFT_MT942' THEN 240
                             ELSE 60 END
                        + (random() * 60 - 30)))::int AS lag
    FROM generate_series(
        date_trunc('day', now()) - interval '90 days',
        date_trunc('day', now()) - interval '1 day',
        interval '1 day') AS gs
) AS d
WHERE pa.data_source IN ('SWIFT_MT940','SWIFT_MT942',
                         'OPEN_BANKING_PSD2','OPEN_BANKING_UK',
                         'OPEN_BANKING_UAE','OPEN_BANKING_KSA')
  AND NOT EXISTS (
        SELECT 1 FROM shadow_sync_log s WHERE s.physical_account_id = pa.id);
