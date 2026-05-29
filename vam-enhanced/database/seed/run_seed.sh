#!/bin/bash

# ==========================================
# VAM Portal - Seed Data Runner
# ==========================================
# Run this script to load all seed data into the database
# ==========================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Default values
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-vam_db}"
DB_USER="${DB_USER:-vam_user}"
DB_PASS="${DB_PASS:-vam_secure_password_123}"

# Script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

print_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

run_sql() {
    local file=$1
    local description=$2
    
    echo -e "${YELLOW}Running: ${description}${NC}"
    
    PGPASSWORD=$DB_PASS psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -f "$file" 2>&1 | \
        grep -E "(NOTICE|ERROR|rows)" || true
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Completed: ${description}${NC}"
    else
        echo -e "${RED}✗ Failed: ${description}${NC}"
        exit 1
    fi
    echo ""
}

show_help() {
    echo "VAM Portal - Seed Data Runner"
    echo ""
    echo "Usage: ./run_seed.sh [command]"
    echo ""
    echo "Commands:"
    echo "  all       Load all seed data (default)"
    echo "  data      Load only business data (no users)"
    echo "  users     Load only demo users"
    echo "  reset     Clear all seed data"
    echo "  help      Show this help"
    echo ""
    echo "Environment Variables:"
    echo "  DB_HOST   Database host (default: localhost)"
    echo "  DB_PORT   Database port (default: 5432)"
    echo "  DB_NAME   Database name (default: vam_db)"
    echo "  DB_USER   Database user (default: vam_user)"
    echo "  DB_PASS   Database password"
    echo ""
    echo "Examples:"
    echo "  ./run_seed.sh all"
    echo "  DB_HOST=192.168.1.100 ./run_seed.sh data"
    echo ""
}

# Main
case "${1:-all}" in
    all)
        print_header "Loading All Seed Data"
        
        echo -e "${YELLOW}Database: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}${NC}"
        echo ""
        
        run_sql "$SCRIPT_DIR/seed_data.sql" "Business data (corporates, accounts, transactions)"
        run_sql "$SCRIPT_DIR/demo_users.sql" "Demo users and roles"
        
        print_header "Seed Data Loaded Successfully!"
        echo -e "${GREEN}You can now log in with:${NC}"
        echo -e "  Username: ${YELLOW}bankadmin${NC}"
        echo -e "  Password: ${YELLOW}Demo@123${NC}"
        ;;
    
    data)
        print_header "Loading Business Data Only"
        run_sql "$SCRIPT_DIR/seed_data.sql" "Business data"
        echo -e "${GREEN}Business data loaded successfully!${NC}"
        ;;
    
    users)
        print_header "Loading Demo Users Only"
        run_sql "$SCRIPT_DIR/demo_users.sql" "Demo users and roles"
        echo -e "${GREEN}Demo users loaded successfully!${NC}"
        ;;
    
    reset)
        print_header "Resetting Seed Data"
        
        echo -e "${RED}WARNING: This will delete all data!${NC}"
        read -p "Are you sure? (y/N): " confirm
        
        if [ "$confirm" = "y" ] || [ "$confirm" = "Y" ]; then
            run_sql "$SCRIPT_DIR/reset_seed_data.sql" "Clearing all data"
            echo -e "${GREEN}Seed data cleared successfully!${NC}"
        else
            echo "Operation cancelled."
        fi
        ;;
    
    help|*)
        show_help
        ;;
esac
