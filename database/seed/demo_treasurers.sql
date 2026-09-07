-- ==========================================================================
-- Demo treasurer accounts for the MCP gateway's login form
-- (com.bank.vam.entity.auth.DemoUser / UserAccountAccess — see
-- docs/mcp-architecture.md's Phase 2). NOT the same schema as the unrelated,
-- older database/seed/demo_users.sql (roles/users/api_keys) — this seeds
-- exactly the two tables Phase 2 revived: users, user_account_access.
--
-- Password for every account below: demo-password-2026
-- Hashed with pgcrypto's crypt(..., gen_salt('bf')) — genuine BCrypt output
-- ($2a$...), compatible with the gateway's BCryptPasswordEncoder.
--
-- Idempotent: safe to re-run (ON CONFLICT DO NOTHING on username / the
-- user+corporate pair). Run against the SAME Postgres the backend and
-- mcp-gateway both point at:
--   psql "$SPRING_DATASOURCE_URL" -f database/seed/demo_treasurers.sql
-- ==========================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (id, username, email, password_hash, display_name, status, version) VALUES
    ('e14a7d78-2125-4c01-b1fa-6e045d57329e', 'mercator.treasurer',  'treasurer@mercatorbrands.example',   crypt('demo-password-2026', gen_salt('bf')), 'Elise Vandermeer', 'ACTIVE', 0),
    ('0288504f-3c31-4ae6-9191-a75f6ab42866', 'albion.treasurer',    'treasurer@albionindustrial.example', crypt('demo-password-2026', gen_salt('bf')), 'James Whitfield',  'ACTIVE', 0),
    ('21307e77-fed6-4e58-b6ac-29363842f9f9', 'almawarid.treasurer', 'treasurer@almawaridtrading.example', crypt('demo-password-2026', gen_salt('bf')), 'Fatima Al-Rashid', 'ACTIVE', 0),
    ('4d14227a-9200-4105-a3fa-53b35f39826f', 'helios.treasurer',    'treasurer@heliosglobal.example',     crypt('demo-password-2026', gen_salt('bf')), 'Marcus Chen',      'ACTIVE', 0),
    ('4661a48b-f407-4916-b453-d71160f6a7b9', 'brato.treasurer',     'treasurer@bratologistics.example',   crypt('demo-password-2026', gen_salt('bf')), 'Amara Osei',       'ACTIVE', 0)
ON CONFLICT (username) DO NOTHING;

-- Corporate-wide VIEW grants — one per treasurer, matching the flagship
-- corporate each is named after (MNC-EUR-001 Mercator, MNC-GBP-003 Albion,
-- MNC-SAR-002 Al Mawarid, MNC-USD-004 Helios, MNC-AED-005 Brato).
INSERT INTO user_account_access (id, user_id, corporate_id, access_level, version)
SELECT gen_random_uuid(), u.id, c.id, 'VIEW', 0
FROM (VALUES
    ('mercator.treasurer',  'MNC-EUR-001'),
    ('albion.treasurer',    'MNC-GBP-003'),
    ('almawarid.treasurer', 'MNC-SAR-002'),
    ('helios.treasurer',    'MNC-USD-004'),
    ('brato.treasurer',     'MNC-AED-005')
) AS grant_map(username, corporate_code)
JOIN users u ON u.username = grant_map.username
JOIN corporates c ON c.corporate_id = grant_map.corporate_code
WHERE NOT EXISTS (
    SELECT 1 FROM user_account_access existing
    WHERE existing.user_id = u.id AND existing.corporate_id = c.id
);
