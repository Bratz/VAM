#!/bin/sh
# ============================================================================
# Container entrypoint: optional one-shot DB seeding, then the app.
#
# When SEED_ON_START=true, loads database/dump/vam_db_full.sql.gz (baked into
# the image) into the configured Postgres — but only if the database looks
# empty (no virtual_accounts table), so leaving the flag on is idempotent.
# Exists for PaaS setups (Render, …) where the developer's machine cannot
# reach the database directly: the container can, over the internal network.
# ============================================================================
set -e

if [ "$SEED_ON_START" = "true" ]; then
  # Derive psql connection pieces from the Spring vars.
  # SPRING_DATASOURCE_URL is jdbc:postgresql://host[:port]/dbname[?params]
  hostport_db=$(echo "$SPRING_DATASOURCE_URL" | sed -e 's|^jdbc:postgresql://||' -e 's|?.*$||')
  hostport=$(echo "$hostport_db" | cut -d/ -f1)
  export PGDATABASE=$(echo "$hostport_db" | cut -d/ -f2)
  export PGHOST=$(echo "$hostport" | cut -d: -f1)
  case "$hostport" in *:*) export PGPORT=$(echo "$hostport" | cut -d: -f2);; *) export PGPORT=5432;; esac
  export PGUSER="$SPRING_DATASOURCE_USERNAME"
  export PGPASSWORD="$DB_PASSWORD"

  if psql -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='virtual_accounts'" 2>/dev/null | grep -q 1; then
    echo "[seed] Database already has schema — skipping seed."
  else
    echo "[seed] Empty database detected at $PGHOST/$PGDATABASE — loading demo dump…"
    # Strip pg18 \restrict/\unrestrict meta-commands the alpine psql may not know.
    gunzip -c /app/seed/vam_db_full.sql.gz | grep -vE '^\\(un)?restrict' | psql -v ON_ERROR_STOP=0 -q
    echo "[seed] Dump loaded."
  fi
fi

exec java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT:-8053}
