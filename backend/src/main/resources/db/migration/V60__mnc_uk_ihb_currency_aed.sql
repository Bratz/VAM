-- ============================================================================
-- V60 - MNC-UK's IHB currency is AED, the currency of its only IHB current account (IHB functional review C4).
--
-- Enabling IHB set ihb_currency to the functional currency, GBP; the IHB current account opened later
-- (IHB-MNC-UK-AED-0002, 12 Sep) is AED, as are the treasury centre and every other IHB account of the group, and
-- MNC-UK never had a GBP one. IhbExposure counts only accounts in the IHB currency, so MNC-UK's exposure read
-- GBP 0.00 and its limit could never warn; the IHB page showed the limit in GBP, the IC Dashboard in AED.
-- IhbUnifiedService now keeps the IHB currency to the accounts' (the first account sets it, others must match).
--
-- GBP -> AED, guarded on the exact state: MNC-UK, IHB-enabled, ihb_currency GBP, with an active AED IHB current account
-- and no IHB current account in any other currency. A no-op anywhere else, and when run again.
-- ============================================================================
DO $$
BEGIN
    IF to_regclass('legal_entities') IS NULL OR to_regclass('virtual_accounts') IS NULL THEN
        RETURN; -- a fresh database: Flyway runs before Hibernate creates the tables
    END IF;
    UPDATE legal_entities le
       SET ihb_currency = 'AED',
           updated_at   = now()
     WHERE le.id = 'e1111111-1111-1111-1111-111111111117'
       AND le.entity_code = 'MNC-UK'
       AND le.ihb_enabled
       AND le.ihb_currency = 'GBP'
       AND EXISTS (SELECT 1 FROM virtual_accounts va
                    WHERE va.owning_entity_id = le.id AND va.ihb_participant AND va.status = 'ACTIVE'
                      AND va.currency_code = 'AED')
       AND NOT EXISTS (SELECT 1 FROM virtual_accounts va
                        WHERE va.owning_entity_id = le.id AND va.ihb_participant AND va.status = 'ACTIVE'
                          AND va.currency_code <> 'AED');
END $$;
