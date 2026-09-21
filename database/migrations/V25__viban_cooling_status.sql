-- V25: numbers cooling off before reuse are no longer counted as available.
--
-- V24's cool-off kept a returned number from being reissued, but it still went
-- back to status RETURNED and into viban_pools.available_count on return. The
-- count drives the exhausted status, low-stock alerts, utilization and the
-- pre-checks in every assign path, so it promised stock that could not be
-- issued. Returned numbers now rest in COOLING and rejoin RETURNED -- and the
-- count -- only when the cool-off passes (VibanService.processExpiredVibans).

ALTER TABLE vibans DROP CONSTRAINT IF EXISTS vibans_status_check;
ALTER TABLE vibans ADD CONSTRAINT vibans_status_check CHECK (status IN
    ('ACTIVE', 'PAID', 'PARTIAL', 'EXPIRED', 'CANCELLED', 'SUSPENDED', 'RETURNED', 'COOLING'));

-- Numbers already returned but still inside the cool-off. 30 days is the
-- default of vam.viban.reuse-cooloff-days; with a shorter setting the
-- scheduler releases them early on its next tick, with a longer one numbers
-- returned 30+ days ago are left available rather than re-quarantined.
-- Never-issued stock (return_scheduled_at IS NULL) is untouched.
UPDATE vibans
SET status = 'COOLING'
WHERE status = 'RETURNED'
  AND pool_id IS NOT NULL
  AND return_scheduled_at IS NOT NULL
  AND return_scheduled_at > now() - interval '30 days';

-- Recompute every pool's count from its rows rather than adjusting it: the
-- incremental counter had already drifted from the stock it describes.
UPDATE viban_pools p
SET available_count = actual.n,
    status = CASE
                 WHEN p.status = 'ACTIVE' AND actual.n = 0 THEN 'EXHAUSTED'
                 WHEN p.status = 'EXHAUSTED' AND actual.n > 0 THEN 'ACTIVE'
                 ELSE p.status
             END
FROM (
    SELECT pl.id, count(v.id) FILTER (WHERE v.status = 'RETURNED') AS n
    FROM viban_pools pl LEFT JOIN vibans v ON v.pool_id = pl.id
    GROUP BY pl.id
) actual
WHERE actual.id = p.id;
