-- ============================================================================
-- V49 - Notional pool references come from a database sequence.
--
-- NotionalPoolService numbered pools NP-0001, NP-0002, ... from an in-memory counter that restarted at 1 with the
-- backend, so the first creations after every restart collided with the unique pool_reference (spec R17).
-- The service now takes nextval('notional_pool_ref_seq'). The sequence starts after the highest NP-<n> already
-- stored; seeded POOL-* references are not numbered and can't collide with NP-*.
--
-- Conditional because on a fresh database Flyway runs before Hibernate creates the table. Safe to rerun:
-- setval only ever moves the sequence forward past the existing references.
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS notional_pool_ref_seq;

DO $$
DECLARE
    highest BIGINT;
BEGIN
    IF to_regclass('public.notional_pools') IS NOT NULL THEN
        SELECT MAX(substring(pool_reference FROM 4)::BIGINT) INTO highest
          FROM public.notional_pools
         WHERE pool_reference ~ '^NP-[0-9]+$';
        IF highest IS NOT NULL THEN
            PERFORM setval('notional_pool_ref_seq', GREATEST(highest, (SELECT last_value FROM notional_pool_ref_seq)));
        END IF;
    END IF;
END $$;
