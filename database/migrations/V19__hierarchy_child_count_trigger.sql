-- V19: make trg_hierarchy_nodes_child_count part of the schema Flyway owns,
-- and repair the drift the old double-maintenance left behind.
--
-- child_count and is_leaf on hierarchy_nodes are derived columns maintained by
-- this trigger. Until now it lived only in database/schema.sql and the pre-baked
-- OCI dump, never in a migration -- so a database built the documented way (empty
-- database + hibernate ddl-auto + Flyway) got the table without the trigger. That
-- was survivable while the service layer also incremented the column by hand in
-- six places; now that those are gone and the trigger is the single owner, it has
-- to be guaranteed to exist everywhere.
--
-- Guarded with IF NOT EXISTS rather than CREATE OR REPLACE: on databases restored
-- from the dump the function is owned by postgres while migrations run as vam_user,
-- and a replace would fail on ownership. The definition below is byte-identical to
-- the one already deployed, so skipping it loses nothing.

DO $outer$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'update_parent_child_count' AND n.nspname = 'public'
    ) THEN
        EXECUTE $fn$
        CREATE FUNCTION public.update_parent_child_count() RETURNS trigger
            LANGUAGE plpgsql
            AS $body$
        BEGIN
            IF TG_OP = 'INSERT' THEN
                IF NEW.parent_id IS NOT NULL THEN
                    UPDATE hierarchy_nodes
                    SET child_count = child_count + 1,
                        is_leaf = false,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = NEW.parent_id;
                END IF;
                RETURN NEW;
            ELSIF TG_OP = 'DELETE' THEN
                IF OLD.parent_id IS NOT NULL THEN
                    UPDATE hierarchy_nodes
                    SET child_count = GREATEST(child_count - 1, 0),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = OLD.parent_id;

                    -- Check if parent should become leaf
                    UPDATE hierarchy_nodes
                    SET is_leaf = true
                    WHERE id = OLD.parent_id
                      AND child_count = 0
                      AND node_type != 'MASTER';
                END IF;
                RETURN OLD;
            END IF;
            RETURN NULL;
        END;
        $body$;
        $fn$;
    END IF;
END
$outer$;

DO $outer$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_hierarchy_nodes_child_count'
          AND tgrelid = 'public.hierarchy_nodes'::regclass
    ) THEN
        CREATE TRIGGER trg_hierarchy_nodes_child_count
            AFTER INSERT OR DELETE ON public.hierarchy_nodes
            FOR EACH ROW EXECUTE FUNCTION public.update_parent_child_count();
    END IF;
END
$outer$;

-- Recompute both columns from the actual children. Two sources of drift are
-- being corrected: createExceptionVaIfNotExists incremented child_count without
-- ever inserting a child node (the exception VA hangs off the parent's own node),
-- and the hand-increments read a pre-insert snapshot, so concurrent creation under
-- one parent could lose a count -- silently, since @Version is disabled.
--
-- is_leaf only moves in the directions the trigger itself moves it: a node with
-- children is not a leaf, a childless non-MASTER node is. A childless MASTER keeps
-- whatever it has, matching the trigger's own node_type != 'MASTER' guard.
UPDATE hierarchy_nodes p
SET child_count = actual.n,
    is_leaf = CASE
                  WHEN actual.n > 0 THEN false
                  WHEN p.node_type != 'MASTER' THEN true
                  ELSE p.is_leaf
              END
FROM (
    SELECT parent.id, count(child.id) AS n
    FROM hierarchy_nodes parent
    LEFT JOIN hierarchy_nodes child ON child.parent_id = parent.id
    GROUP BY parent.id
) actual
WHERE actual.id = p.id
  AND (p.child_count IS DISTINCT FROM actual.n
       OR p.is_leaf IS DISTINCT FROM CASE
                                         WHEN actual.n > 0 THEN false
                                         WHEN p.node_type != 'MASTER' THEN true
                                         ELSE p.is_leaf
                                     END);
