-- V22: every program can have a hierarchy, VIBANs and wallets, so the three
-- flags that pretended otherwise go.
--
-- hierarchy_enabled was a creation-time option stored as state. Its one unique
-- job was deciding whether createProgram bootstrapped a tree; it also guarded
-- createNode and createWithDimensions -- while createWithParentNode, the path
-- accounts are actually created through, never checked it. Every program now
-- bootstraps a root at creation (and existing ones get one at startup), and
-- "does this program have a hierarchy?" is root_hierarchy_node_id IS NOT NULL.
--
-- wallet_enabled decided which programs the Wallet page listed. Wallet accounts
-- were already identified per account by wallet_type, so balances, limits and
-- operations never depended on it. The page now lists every program for
-- issuance and counts, in its stats, the programs that actually hold wallets.
--
-- viban_enabled decided nothing server-side: VibanService never read it. It
-- only toggled a wizard step and a tab.
ALTER TABLE unified_programs
    DROP COLUMN IF EXISTS hierarchy_enabled,
    DROP COLUMN IF EXISTS viban_enabled,
    DROP COLUMN IF EXISTS wallet_enabled;
