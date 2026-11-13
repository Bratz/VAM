-- ============================================================================
-- V62 - a netting cycle belongs to one corporate (IHB/Intercompany pages, Phase 2).
--
-- Cycles had no corporate: Netting Cycles listed every corporate's cycles, populate took every corporate's
-- recharges, and an empty cycle was offered to every corporate. NettingCycle.corporateId maps netting_cycles.corporate_id;
-- the column is added here (as ddl-auto would: uuid, nullable) because Flyway runs before Hibernate and the backfill
-- needs it.
--
-- Backfill: a cycle with no corporate takes the corporate of its entries' entities when every payer and payee of
-- every entry is an entity of that one corporate. Cycles with no entries, or with entities of several corporates (or
-- unknown ones), stay null: no corporate sees them. On the vam-five dump: NET-202602-003 -> Test Multinational Corp;
-- NET-202601-001, NET-202602-001, NET-202602-002 (empty DRAFTs) and NET-202411-ALBN, NET-202412-MERC (PENDING_APPROVAL,
-- entry_count 28 / 42 but no entry rows) stay null.
--
-- Only null corporate_id is written. Safe to run twice.
-- ============================================================================
DO $$
BEGIN
    IF to_regclass('netting_cycles') IS NULL THEN
        RETURN; -- a fresh database: Flyway runs before Hibernate creates the tables
    END IF;
    ALTER TABLE netting_cycles ADD COLUMN IF NOT EXISTS corporate_id uuid;

    IF to_regclass('netting_entries') IS NULL OR to_regclass('legal_entities') IS NULL THEN
        RETURN;
    END IF;
    UPDATE netting_cycles c
       SET corporate_id = one.corporate_id
      FROM (SELECT p.cycle_id, min(le.corporate_id::text)::uuid AS corporate_id
              FROM (SELECT cycle_id, payer_entity_id AS entity_id FROM netting_entries
                    UNION ALL
                    SELECT cycle_id, payee_entity_id FROM netting_entries) p
              LEFT JOIN legal_entities le ON le.id = p.entity_id
             GROUP BY p.cycle_id
            HAVING count(DISTINCT le.corporate_id) = 1
               AND count(*) = count(le.corporate_id)) one
     WHERE c.id = one.cycle_id
       AND c.corporate_id IS NULL;
END $$;
