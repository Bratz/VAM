-- Cleanup: remap Acme entities' receivables onto MNC group, then delete the 4
-- orphaned Acme legal entities (HQ-UAE, SUB-DUBAI, SUB-ABUDHABI, SUB-SHARJAH)
-- and their 3 virtual accounts (VA-HQ-001, VA-AD-001, VA-DUBAI-001).
--
-- Run with:  psql -h localhost -U vam_user -d vam_db -v ON_ERROR_STOP=1 -f cleanup_acme_entities.sql
-- (password: vam_user123, or whatever DB_PASSWORD is set to in your environment)

BEGIN;

-- Remap the 18 receivables from the 4 Acme entities onto their geographic MNC
-- counterparts:
--   HQ-UAE (holding)        -> MNC-HOLDING (holding)
--   SUB-DUBAI (Dubai)       -> MNC-UAE-DUBAI (Dubai branch)
--   SUB-ABUDHABI (AbuDhabi) -> MNC-UAE-ABUDHABI (Abu Dhabi branch)
--   SUB-SHARJAH (no MNC Sharjah entity) -> MNC-UAE (UAE umbrella subsidiary)

UPDATE receivables SET owning_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'MNC-HOLDING')
WHERE owning_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'HQ-UAE');
UPDATE receivables SET intercompany_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'MNC-HOLDING')
WHERE intercompany_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'HQ-UAE');

UPDATE receivables SET owning_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'MNC-UAE-DUBAI')
WHERE owning_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'SUB-DUBAI');
UPDATE receivables SET intercompany_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'MNC-UAE-DUBAI')
WHERE intercompany_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'SUB-DUBAI');

UPDATE receivables SET owning_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'MNC-UAE-ABUDHABI')
WHERE owning_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'SUB-ABUDHABI');
UPDATE receivables SET intercompany_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'MNC-UAE-ABUDHABI')
WHERE intercompany_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'SUB-ABUDHABI');

UPDATE receivables SET owning_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'MNC-UAE')
WHERE owning_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'SUB-SHARJAH');
UPDATE receivables SET intercompany_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'MNC-UAE')
WHERE intercompany_entity_id = (SELECT id FROM legal_entities WHERE entity_code = 'SUB-SHARJAH');

-- Now the 4 Acme entities and their 3 VAs are fully unreferenced; delete them.
DELETE FROM va_movements WHERE va_id IN (
  SELECT id FROM virtual_accounts WHERE va_number IN ('VA-HQ-001','VA-AD-001','VA-DUBAI-001')
);
DELETE FROM virtual_accounts WHERE va_number IN ('VA-HQ-001','VA-AD-001','VA-DUBAI-001');
DELETE FROM legal_entities WHERE entity_code IN ('HQ-UAE','SUB-DUBAI','SUB-ABUDHABI','SUB-SHARJAH');

COMMIT;

-- Verification queries (run after commit):
-- SELECT entity_code FROM legal_entities WHERE entity_code IN ('HQ-UAE','SUB-DUBAI','SUB-ABUDHABI','SUB-SHARJAH'); -- should return 0 rows
-- SELECT va_number FROM virtual_accounts WHERE va_number IN ('VA-HQ-001','VA-AD-001','VA-DUBAI-001'); -- should return 0 rows
-- SELECT le.entity_code, count(*) FROM receivables r JOIN legal_entities le ON le.id = r.owning_entity_id
--   WHERE le.entity_code LIKE 'MNC%' GROUP BY le.entity_code ORDER BY 1; -- should show the reassigned counts
