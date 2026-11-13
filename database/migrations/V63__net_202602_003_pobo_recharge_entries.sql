-- ============================================================================
-- V63 - NET-202602-003 (DRAFT) loses the six POBO payment recharge entries it should never have held.
--
-- The cycle "Q4" holds six PENDING entries, Dubai owing treasury: RCH-IHBPOBO-1770051223508-5266 (AED 151) four times
-- and RCH-IHBPOBO-1770391620201-5585 (AED 245) twice, added by repeated populates before netting stopped taking
-- POBO_PAYMENT recharges and before a recharge already in a cycle was skipped. A POBO payment is settled through
-- Dubai's IHB current account when it posts (POBO R11; on vam-five the cut-over also marked both recharges SETTLED,
-- IHB-POSITION-CUTOVER), so settling this cycle would charge Dubai AED 1,094 for AED 396 of payments already borne.
--
-- Removes those entries rather than cancelling the cycle: the cycle stays the treasurer's DRAFT, usable for real
-- obligations; cancelling would keep rows that duplicate the recharges and make them look netted. Its entry_count and
-- total_gross are then recomputed from what is left (0, 0.00). Runs after V62, which gave the cycle its corporate
-- (TestMNC) from these entries.
--
-- Guarded on the exact rows: the cycle by id, reference and DRAFT; each entry by id, cycle, PENDING status,
-- POBO_RECHARGE source, recharge id and amount; each recharge being a POBO_PAYMENT. Anything different is left alone.
-- Safe to run twice.
-- ============================================================================
DO $$
BEGIN
    IF to_regclass('netting_cycles') IS NULL OR to_regclass('netting_entries') IS NULL
       OR to_regclass('intercompany_recharges') IS NULL THEN
        RETURN;
    END IF;

    DELETE FROM netting_entries e
     USING netting_cycles c, intercompany_recharges r,
           (VALUES ('18a56794-edca-4191-859b-f6b1e75a8106'::uuid, '1503e972-0a35-4df7-a99b-3d0d0396b0cb'::uuid, 151.00),
                   ('56e96b78-8c85-48ce-b4e2-507ba16b7898'::uuid, '1503e972-0a35-4df7-a99b-3d0d0396b0cb'::uuid, 151.00),
                   ('144fa0a2-6a84-403d-b033-972ef8222647'::uuid, '1503e972-0a35-4df7-a99b-3d0d0396b0cb'::uuid, 151.00),
                   ('789b528c-a853-453d-8aad-b5b55b711e7b'::uuid, '1503e972-0a35-4df7-a99b-3d0d0396b0cb'::uuid, 151.00),
                   ('edd4aba8-868b-49e6-bc8c-bd65c4497284'::uuid, 'd48b88c3-de98-49b9-921f-0d6fd4fab81c'::uuid, 245.00),
                   ('74084f43-29ac-40f0-b1b7-1c47e30c0578'::uuid, 'd48b88c3-de98-49b9-921f-0d6fd4fab81c'::uuid, 245.00)
           ) AS x(entry_id, recharge_id, amount)
     WHERE e.id = x.entry_id
       AND e.cycle_id = c.id
       AND c.id = '2bdad582-1aad-4abe-aa85-b53b3d81e6c9'
       AND c.cycle_reference = 'NET-202602-003'
       AND c.status = 'DRAFT'
       AND e.status = 'PENDING'
       AND e.source_type = 'POBO_RECHARGE'
       AND e.intercompany_recharge_id = x.recharge_id
       AND e.gross_amount = x.amount
       AND r.id = x.recharge_id
       AND r.recharge_type = 'POBO_PAYMENT';

    UPDATE netting_cycles c
       SET entry_count = t.n,
           total_gross = t.gross,
           updated_at  = now()
      FROM (SELECT count(e.id)::int AS n, coalesce(sum(e.base_amount), 0) AS gross
              FROM netting_cycles c2 LEFT JOIN netting_entries e ON e.cycle_id = c2.id
             WHERE c2.id = '2bdad582-1aad-4abe-aa85-b53b3d81e6c9') t
     WHERE c.id = '2bdad582-1aad-4abe-aa85-b53b3d81e6c9'
       AND c.cycle_reference = 'NET-202602-003'
       AND c.status = 'DRAFT'
       AND (c.entry_count IS DISTINCT FROM t.n OR c.total_gross IS DISTINCT FROM t.gross);
END $$;
