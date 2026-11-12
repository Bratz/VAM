-- ============================================================================
-- V55 - External-bank sweeps: seeded rules re-pointed to the home bank, demo authorities (spec R7, D1, D9, D10).
--
-- An account at another bank (an EXTERNAL_MIRROR, or a PHYSICAL_MIRROR whose BIC is not the home bank's) may only be a
-- sweep SOURCE, swept into its own legal entity's account at the home bank in the rule's currency, under an ACTIVE
-- authority (SweepService.assertExternalLegsFit / assertShadowLegEligible). The home bank is the configured
-- vam.home-bank.bic, passed in as ${homebankbic}; an 8-character BIC = its 11-character "XXX" form, as
-- HomeBankProperties.sameBank (same handling as V53).
--
-- Every rule not DISABLED with a leg at another bank:
--   - Target = an ACTIVE PHYSICAL_MIRROR held at the home bank, of the legal entity of the rule's sources (one
--     entity), in the rule currency, not one of the rule's sources; the current target if it already qualifies,
--     else the earliest-opened. The demo data has one source per rule.
--   - None (sources of no / several entities, or the entity holds no such account): the rule is DISABLED and the
--     reason appended to its name, e.g. "(off: no GBP home-bank account of ALBN-EN)" (sweep_rules has no reason column;
--     DISABLED cannot be toggled back on; V30/V31 disable the same way).
--     No cross-entity fallback (D10) and no account is made up for it.
--   - Kept rules: one ACTIVE MT101 demo authority per source at another bank ('DEMO-AUTH-<account number>', no limits,
--     valid from 2026-01-01), unless the account already has an ACTIVE one.
--
-- Which rules keep running depends on the home bank and on which home-bank accounts exist. The demo corporates' head
-- entities get home-bank accounts from FxSettlementAccountSeeder (USD/EUR/GBP + functional currency), which runs at
-- startup AFTER Flyway: on a running instance they exist, on a fresh instance seeded from the dump they do not yet.
--   EBILAEADXXX (UAE, vam-five): Brato Sharjah -> Dubai keeps its target (already home bank, same entity), Helios and
--     Al Mawarid rules move to the head entity's EBILAEAD account; Albion (ALBN-EN) and Mercator (MERC-FR, MERC-IT)
--     rules are disabled: those entities hold no account at the home bank. Fresh from the dump: only Brato Sharjah
--     is kept. Brato Abu Dhabi -> Dubai is home-bank only and untouched.
--   HBUKGB4BXXX (UK, local default): Albion Manchester -> ALBN0000003 (ALBN-EN's own account at the home bank);
--     Brato, Helios and Al Mawarid rules -> the head entity's HBUKGB4B account; Albion Birmingham (its source is that
--     only ALBN-EN account) and the Mercator rules are disabled.
--
-- No-op where the tables don't exist (fresh database: Flyway runs before Hibernate creates them) and when run again.
-- instruction_format is a ddl-auto column (Phase 1): set where it exists; where it doesn't yet, NULL reads as MT101.
-- ponytail: physical_accounts.sweep_enabled / sweep_rule_id (a display mirror of rule membership) are left as they are.
-- ============================================================================

DO $$
DECLARE
    home      text := upper(trim('${homebankbic}'));
    has_fmt   boolean;
    r         record;
    n_ent     int;
    n_src     int;
    ent       uuid;
    ent_code  text;
    tgt_id    uuid;
    tgt_no    text;
    reason    text;
    kept      int := 0;
    disabled  int := 0;
BEGIN
    IF to_regclass('sweep_rules') IS NULL OR to_regclass('sweep_rule_sources') IS NULL
       OR to_regclass('virtual_accounts') IS NULL OR to_regclass('external_mandates') IS NULL THEN
        RETURN;
    END IF;
    IF length(home) = 8 THEN
        home := home || 'XXX';
    END IF;
    has_fmt := EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name = 'external_mandates'
                         AND column_name = 'instruction_format');

    -- Accounts at another bank.
    CREATE TEMP TABLE v55_other_bank ON COMMIT DROP AS
        SELECT v.id FROM virtual_accounts v
         WHERE v.account_category = 'EXTERNAL_MIRROR'
            OR (v.account_category = 'PHYSICAL_MIRROR'
                AND (CASE WHEN length(trim(v.bank_swift)) = 8 THEN upper(trim(v.bank_swift)) || 'XXX'
                          ELSE upper(trim(v.bank_swift)) END) IS DISTINCT FROM home);

    FOR r IN SELECT sr.id, sr.rule_reference, sr.rule_name, sr.currency_code, sr.target_account_id FROM sweep_rules sr
             WHERE sr.status IS DISTINCT FROM 'DISABLED'
               AND (sr.target_account_id IN (SELECT id FROM v55_other_bank)
                    OR EXISTS (SELECT 1 FROM sweep_rule_sources s
                               WHERE s.rule_id = sr.id AND s.account_id IN (SELECT id FROM v55_other_bank)))
             ORDER BY sr.rule_reference
    LOOP
        SELECT count(*), count(DISTINCT v.owning_entity_id), min(v.owning_entity_id::text)::uuid, min(v.owning_entity_code)
          INTO n_src, n_ent, ent, ent_code
          FROM sweep_rule_sources s JOIN virtual_accounts v ON v.id = s.account_id
         WHERE s.rule_id = r.id;
        IF n_ent = 1 AND EXISTS (SELECT 1 FROM sweep_rule_sources s JOIN virtual_accounts v ON v.id = s.account_id
                                 WHERE s.rule_id = r.id AND v.owning_entity_id IS NULL) THEN
            n_ent := 2;   -- an unassigned source: not one entity
        END IF;

        tgt_id := NULL;
        tgt_no := NULL;
        IF n_ent = 1 THEN
            SELECT v.id, v.va_number INTO tgt_id, tgt_no
              FROM virtual_accounts v
             WHERE v.account_category = 'PHYSICAL_MIRROR' AND v.status = 'ACTIVE'
               AND v.id NOT IN (SELECT id FROM v55_other_bank)
               AND v.owning_entity_id = ent
               AND upper(v.currency_code) = upper(r.currency_code)
               AND v.id NOT IN (SELECT s.account_id FROM sweep_rule_sources s WHERE s.rule_id = r.id)
             ORDER BY (v.id = r.target_account_id) DESC, v.created_at NULLS LAST, v.va_number
             LIMIT 1;
        END IF;

        IF tgt_id IS NULL THEN
            reason := CASE WHEN n_src = 0 THEN 'no source account'
                           WHEN n_ent <> 1 THEN 'sources of several legal entities'
                           ELSE 'no ' || r.currency_code || ' home-bank account of ' || coalesce(ent_code, 'its entity') END;
            UPDATE sweep_rules
               SET status = 'DISABLED',
                   rule_name = left(rule_name || ' (off: ' || reason || ')', 100),
                   updated_at = now(), updated_by = 'V55'
             WHERE id = r.id;
            disabled := disabled + 1;
            RAISE NOTICE 'Rule % (%): disabled, %', r.rule_reference, r.rule_name, reason;
            CONTINUE;
        END IF;

        IF tgt_id IS DISTINCT FROM r.target_account_id THEN
            UPDATE sweep_rules
               SET target_account_id = tgt_id, target_account_number = tgt_no, target_entity_code = ent_code,
                   updated_at = now(), updated_by = 'V55'
             WHERE id = r.id;
        END IF;

        INSERT INTO external_mandates (id, created_at, created_by, updated_at, updated_by, version,
                                       creditor_bank_bic, debitor_bank_bic, document_ref, mandate_reference,
                                       mandate_type, status, shadow_va_id, valid_from)
        SELECT DISTINCT ON (v.id) gen_random_uuid(), now(), 'V55', now(), 'V55', 0,
               home, left(trim(v.bank_swift), 11), 'Demo authority (V55)', 'DEMO-AUTH-' || v.va_number,
               'STANDING_INSTRUCTION', 'ACTIVE', v.id, DATE '2026-01-01'
          FROM sweep_rule_sources s JOIN virtual_accounts v ON v.id = s.account_id
         WHERE s.rule_id = r.id AND v.id IN (SELECT id FROM v55_other_bank)
           AND NOT EXISTS (SELECT 1 FROM external_mandates m WHERE m.mandate_reference = 'DEMO-AUTH-' || v.va_number)
           AND NOT EXISTS (SELECT 1 FROM external_mandates m WHERE m.shadow_va_id = v.id AND m.status = 'ACTIVE');

        kept := kept + 1;
        RAISE NOTICE 'Rule % (%): target %', r.rule_reference, r.rule_name, tgt_no;
    END LOOP;

    IF has_fmt THEN
        EXECUTE 'UPDATE external_mandates SET instruction_format = ''MT101'''
             || ' WHERE created_by = ''V55'' AND instruction_format IS NULL';
    END IF;
    RAISE NOTICE 'V55: % rule(s) kept at the home bank, % disabled (home bank %)', kept, disabled, home;
END $$;
