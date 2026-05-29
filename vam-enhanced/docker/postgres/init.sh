#!/bin/bash
# ==========================================
# VAM Portal - PostgreSQL Initialization
# ==========================================
# This script runs when the PostgreSQL container starts
# ==========================================

set -e

echo "=========================================="
echo "  VAM Portal - Database Initialization"
echo "=========================================="

# Enable extensions
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
    CREATE EXTENSION IF NOT EXISTS "pgcrypto";
    
    -- Grant permissions
    GRANT ALL ON SCHEMA public TO $POSTGRES_USER;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO $POSTGRES_USER;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO $POSTGRES_USER;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON FUNCTIONS TO $POSTGRES_USER;
EOSQL

echo "Extensions enabled."

# Run migrations in order
echo "Running migrations..."

for migration in /docker-entrypoint-initdb.d/migrations/*.sql; do
    if [ -f "$migration" ]; then
        echo "Applying: $(basename $migration)"
        psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f "$migration"
    fi
done

echo "Migrations complete."

# Load seed data if available
if [ -f "/docker-entrypoint-initdb.d/seed/seed_data.sql" ]; then
    echo "Loading seed data..."
    psql -v ON_ERROR_STOP=0 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f "/docker-entrypoint-initdb.d/seed/seed_data.sql"
    echo "Seed data loaded."
fi

if [ -f "/docker-entrypoint-initdb.d/seed/demo_users.sql" ]; then
    echo "Loading demo users..."
    psql -v ON_ERROR_STOP=0 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f "/docker-entrypoint-initdb.d/seed/demo_users.sql"
    echo "Demo users loaded."
fi

echo "=========================================="
echo "  Database initialization complete!"
echo "=========================================="
