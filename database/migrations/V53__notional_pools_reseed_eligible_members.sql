-- ============================================================================
-- V53 - Demo notional pools re-seeded with eligible members (notional pooling spec R18, D10).
--
-- NP-0001 and NP-0002 (a ROOT, a TRANSACTION and a CURRENCY_MIRROR account among their members) are closed: their
-- members leave today.
--
-- The seeded pools POOL-ALBN-GBP, POOL-BRTO-AED, POOL-HEL-USD, POOL-MAWR-SAR and POOL-MERC-EUR keep only members the
-- pool service would admit (NotionalPoolService.admit): an ACTIVE PHYSICAL_MIRROR held at the home bank (the configured
-- vam.home-bank.bic, passed in as the ${homebankbic} placeholder; an 8-character BIC = its 11-character "XXX" form, as
-- HomeBankProperties.sameBank), in the pool's currency, of the pool's corporate, of ONE legal entity, in no other pool.
--   - Header: the current header if it qualifies, else the earliest-joined member that does. The pool's legal entity
--     becomes the header's; members of another entity leave.
--   - A member that does not qualify leaves (INACTIVE, row kept) dated the day it joined: this is a re-seed, so the
--     history the daily runs build never counts it.
--   - A pool with no qualifying member is closed (members leave today): no account is made up for it.
--   - The hard-coded figures go: interest_savings_ytd and the members' interest_allocation are set to 0 (unused;
--     the screens show figures from the daily runs). Unposted (CALCULATED) runs of a pool whose membership changed
--     are deleted so the catch-up rebuilds them; posted runs are ledger history and stay.
-- The seed (database/seed/global_mncs_pooling.sql) ends with the same block, for fresh databases.
--
-- Which pools keep members depends on the home bank: each demo corporate banks at its own country's banks.
-- EBILAEADXXX (UAE default, vam-five): POOL-BRTO-AED keeps BRTO0000001 (header) + BRTO0000002; the other four close.
--
-- No-op where the pools don't exist, on a fresh database (Flyway runs before Hibernate creates the tables), and when
-- run again. header_account_id / legal_entity_id are ddl-auto columns (Phase 2): written only where they exist; where
-- they don't yet, the startup backfill sets them to the earliest-joined member and its entity, which is the same pick.
-- ============================================================================

DO $$
DECLARE
    home      text := upper(trim('${homebankbic}'));
    has_hdr   boolean;
    has_runs  boolean;
    p         record;
    cur_hdr   uuid;
    hdr       uuid;
    ent       uuid;
    elig      uuid[];
    leaving   uuid[];
BEGIN
    IF to_regclass('public.notional_pools') IS NULL OR to_regclass('public.pool_members') IS NULL THEN
        RETURN;
    END IF;
    IF length(home) = 8 THEN
        home := home || 'XXX';
    END IF;
    has_hdr := EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = 'public' AND table_name = 'notional_pools' AND column_name = 'header_account_id');
    has_runs := to_regclass('public.pool_interest_calculations') IS NOT NULL
                AND to_regclass('public.pool_interest_lines') IS NOT NULL;

    FOR p IN SELECT id, pool_reference, pool_currency, corporate_id FROM notional_pools
             WHERE status <> 'CLOSED'
               AND pool_reference IN ('NP-0001', 'NP-0002',
                                      'POOL-ALBN-GBP', 'POOL-BRTO-AED', 'POOL-HEL-USD', 'POOL-MAWR-SAR', 'POOL-MERC-EUR')
             ORDER BY pool_reference
    LOOP
        hdr := NULL;
        ent := NULL;
        IF p.pool_reference LIKE 'POOL-%' THEN
            -- Active members the pool service would admit, entity aside.
            SELECT array_agg(m.account_id) INTO elig
              FROM pool_members m JOIN virtual_accounts v ON v.id = m.account_id
             WHERE m.pool_id = p.id AND m.status = 'ACTIVE'
               AND v.status = 'ACTIVE' AND v.account_category = 'PHYSICAL_MIRROR'
               AND upper(v.currency_code) = upper(p.pool_currency)
               AND v.corporate_id = p.corporate_id
               AND v.owning_entity_id IS NOT NULL
               AND (CASE WHEN length(trim(v.bank_swift)) = 8 THEN upper(trim(v.bank_swift)) || 'XXX'
                         ELSE upper(trim(v.bank_swift)) END) = home
               AND NOT EXISTS (SELECT 1 FROM pool_members o
                               WHERE o.account_id = m.account_id AND o.status = 'ACTIVE' AND o.pool_id <> p.id);

            cur_hdr := NULL;
            IF has_hdr THEN
                EXECUTE 'SELECT header_account_id FROM notional_pools WHERE id = $1' INTO cur_hdr USING p.id;
            END IF;
            SELECT m.account_id, v.owning_entity_id INTO hdr, ent
              FROM pool_members m JOIN virtual_accounts v ON v.id = m.account_id
             WHERE m.pool_id = p.id AND m.status = 'ACTIVE' AND m.account_id = ANY (coalesce(elig, '{}'))
             ORDER BY (m.account_id = cur_hdr) DESC NULLS LAST, m.joined_date NULLS LAST, m.created_at NULLS LAST, m.account_id
             LIMIT 1;
        END IF;

        -- Leaving: everyone in NP-0001/0002 and in a pool with no qualifying member; else who doesn't qualify or is
        -- of another entity than the header's.
        SELECT array_agg(m.id) INTO leaving
          FROM pool_members m JOIN virtual_accounts v ON v.id = m.account_id
         WHERE m.pool_id = p.id AND m.status = 'ACTIVE'
           AND (hdr IS NULL OR NOT (m.account_id = ANY (elig) AND v.owning_entity_id = ent));
        -- (a member whose account row is gone is left alone; no such member exists in the demo data)

        IF leaving IS NOT NULL THEN
            -- Their physical accounts are no longer flagged as pooled (as NotionalPoolService.leave).
            UPDATE physical_accounts pa
               SET pooling_enabled = false, pool_id = NULL, pool_reference = NULL
              FROM pool_members m JOIN virtual_accounts v ON v.id = m.account_id
             WHERE m.id = ANY (leaving) AND pa.id = v.physical_account_id AND (pa.pool_id IS NULL OR pa.pool_id = p.id);
            UPDATE pool_members
               SET status = 'INACTIVE',
                   left_date = CASE WHEN hdr IS NULL THEN CURRENT_DATE ELSE coalesce(joined_date, CURRENT_DATE) END,
                   updated_at = now()
             WHERE id = ANY (leaving);
            IF has_runs THEN
                DELETE FROM pool_interest_lines WHERE calculation_id IN
                    (SELECT id FROM pool_interest_calculations WHERE pool_id = p.id AND status = 'CALCULATED');
                DELETE FROM pool_interest_calculations WHERE pool_id = p.id AND status = 'CALCULATED';
                UPDATE notional_pools SET last_calculation_date = (SELECT max(calculation_date) FROM pool_interest_calculations
                                                                   WHERE pool_id = p.id AND status <> 'SUPERSEDED')
                 WHERE id = p.id;
            END IF;
        END IF;

        IF hdr IS NULL THEN
            UPDATE notional_pools SET status = 'CLOSED', effective_to = CURRENT_DATE, updated_at = now() WHERE id = p.id;
        ELSIF has_hdr THEN
            EXECUTE 'UPDATE notional_pools SET header_account_id = $2, legal_entity_id = $3 WHERE id = $1' USING p.id, hdr, ent;
        END IF;
        UPDATE notional_pools
           SET interest_savings_ytd = 0,
               member_count = (SELECT count(*) FROM pool_members WHERE pool_id = p.id AND status = 'ACTIVE'),
               updated_at = now()
         WHERE id = p.id;
        UPDATE pool_members SET interest_allocation = 0 WHERE pool_id = p.id;

        RAISE NOTICE 'Pool %: % (header %, % left)', p.pool_reference,
            CASE WHEN hdr IS NULL THEN 'closed' ELSE 'kept' END, hdr, coalesce(cardinality(leaving), 0);
    END LOOP;
END $$;
