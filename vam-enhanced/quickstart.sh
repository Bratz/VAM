#!/bin/bash

# ==========================================
# VAM Portal - Quick Start Script
# ==========================================
# This script helps you set up and run the VAM Portal locally
# ==========================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Print colored message
print_msg() {
    echo -e "${2}${1}${NC}"
}

print_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

# Check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check prerequisites
check_prerequisites() {
    print_header "Checking Prerequisites"
    
    local all_ok=true
    
    # Java
    if command_exists java; then
        JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
        if [ "$JAVA_VERSION" -ge 21 ]; then
            print_msg "✓ Java $JAVA_VERSION found" "$GREEN"
        else
            print_msg "✗ Java 21+ required, found $JAVA_VERSION" "$RED"
            all_ok=false
        fi
    else
        print_msg "✗ Java not found" "$RED"
        all_ok=false
    fi
    
    # Maven
    if command_exists mvn; then
        MVN_VERSION=$(mvn -version 2>&1 | head -n 1 | grep -oP '\d+\.\d+')
        print_msg "✓ Maven $MVN_VERSION found" "$GREEN"
    else
        print_msg "✗ Maven not found" "$RED"
        all_ok=false
    fi
    
    # Node.js
    if command_exists node; then
        NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
        if [ "$NODE_VERSION" -ge 18 ]; then
            print_msg "✓ Node.js v$NODE_VERSION found" "$GREEN"
        else
            print_msg "✗ Node.js 18+ required, found v$NODE_VERSION" "$RED"
            all_ok=false
        fi
    else
        print_msg "✗ Node.js not found" "$RED"
        all_ok=false
    fi
    
    # npm
    if command_exists npm; then
        NPM_VERSION=$(npm -v)
        print_msg "✓ npm $NPM_VERSION found" "$GREEN"
    else
        print_msg "✗ npm not found" "$RED"
        all_ok=false
    fi
    
    # PostgreSQL
    if command_exists psql; then
        PSQL_VERSION=$(psql --version | grep -oP '\d+' | head -1)
        print_msg "✓ PostgreSQL $PSQL_VERSION found" "$GREEN"
    else
        print_msg "✗ PostgreSQL not found" "$YELLOW"
        print_msg "  (Optional if using Docker)" "$YELLOW"
    fi
    
    # Docker (optional)
    if command_exists docker; then
        print_msg "✓ Docker found" "$GREEN"
    else
        print_msg "○ Docker not found (optional)" "$YELLOW"
    fi
    
    if [ "$all_ok" = false ]; then
        print_msg "\nPlease install missing prerequisites before continuing." "$RED"
        exit 1
    fi
    
    print_msg "\nAll prerequisites satisfied!" "$GREEN"
}

# Setup database
setup_database() {
    print_header "Setting Up Database"
    
    read -p "Enter PostgreSQL host [localhost]: " DB_HOST
    DB_HOST=${DB_HOST:-localhost}
    
    read -p "Enter PostgreSQL port [5432]: " DB_PORT
    DB_PORT=${DB_PORT:-5432}
    
    read -p "Enter PostgreSQL superuser [postgres]: " DB_SUPERUSER
    DB_SUPERUSER=${DB_SUPERUSER:-postgres}
    
    read -sp "Enter PostgreSQL superuser password: " DB_SUPERPASS
    echo ""
    
    print_msg "\nCreating database and user..." "$BLUE"
    
    PGPASSWORD=$DB_SUPERPASS psql -h $DB_HOST -p $DB_PORT -U $DB_SUPERUSER << EOF
-- Create user if not exists
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vam_user') THEN
        CREATE USER vam_user WITH PASSWORD 'vam_secure_password_123';
    END IF;
END
\$\$;

-- Create database if not exists
SELECT 'CREATE DATABASE vam_db OWNER vam_user'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'vam_db')\gexec

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE vam_db TO vam_user;

-- Connect to vam_db and setup extensions
\c vam_db

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Grant schema privileges
GRANT ALL ON SCHEMA public TO vam_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO vam_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO vam_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON FUNCTIONS TO vam_user;

\q
EOF
    
    print_msg "Database setup complete!" "$GREEN"
}

# Setup backend
setup_backend() {
    print_header "Setting Up Backend"
    
    cd backend
    
    # Create migration directory if not exists
    mkdir -p src/main/resources/db/migration
    
    # Copy migrations if they exist in database folder
    if [ -d "../database/migrations" ]; then
        print_msg "Copying database migrations..." "$BLUE"
        cp -n ../database/migrations/*.sql src/main/resources/db/migration/ 2>/dev/null || true
    fi
    
    print_msg "Building backend (this may take a few minutes)..." "$BLUE"
    mvn clean install -DskipTests -q
    
    print_msg "Backend build complete!" "$GREEN"
    cd ..
}

# Setup frontend
setup_frontend() {
    print_header "Setting Up Frontend"
    
    cd frontend
    
    print_msg "Installing dependencies..." "$BLUE"
    npm install
    
    # Create .env if not exists
    if [ ! -f ".env" ]; then
        print_msg "Creating .env file..." "$BLUE"
        cat > .env << 'EOF'
VITE_API_BASE_URL=http://localhost:8080
VITE_API_TIMEOUT=30000
VITE_ENABLE_MOCK_DATA=true
VITE_ENABLE_DEBUG=true
VITE_APP_NAME=VAM Portal
VITE_APP_VERSION=1.0.0
EOF
    fi
    
    print_msg "Frontend setup complete!" "$GREEN"
    cd ..
}

# Run backend
run_backend() {
    print_header "Starting Backend"
    
    cd backend
    print_msg "Starting Spring Boot application..." "$BLUE"
    mvn spring-boot:run -Dspring-boot.run.profiles=dev &
    BACKEND_PID=$!
    echo $BACKEND_PID > ../backend.pid
    cd ..
    
    print_msg "Backend starting on http://localhost:8080" "$GREEN"
    print_msg "PID: $BACKEND_PID" "$YELLOW"
}

# Run frontend
run_frontend() {
    print_header "Starting Frontend"
    
    cd frontend
    print_msg "Starting Vite development server..." "$BLUE"
    npm run dev &
    FRONTEND_PID=$!
    echo $FRONTEND_PID > ../frontend.pid
    cd ..
    
    print_msg "Frontend starting on http://localhost:3000" "$GREEN"
    print_msg "PID: $FRONTEND_PID" "$YELLOW"
}

# Stop services
stop_services() {
    print_header "Stopping Services"
    
    if [ -f "backend.pid" ]; then
        BACKEND_PID=$(cat backend.pid)
        if kill -0 $BACKEND_PID 2>/dev/null; then
            kill $BACKEND_PID
            print_msg "Backend stopped (PID: $BACKEND_PID)" "$GREEN"
        fi
        rm backend.pid
    fi
    
    if [ -f "frontend.pid" ]; then
        FRONTEND_PID=$(cat frontend.pid)
        if kill -0 $FRONTEND_PID 2>/dev/null; then
            kill $FRONTEND_PID
            print_msg "Frontend stopped (PID: $FRONTEND_PID)" "$GREEN"
        fi
        rm frontend.pid
    fi
    
    # Also kill any processes on the ports
    lsof -ti:8080 | xargs kill -9 2>/dev/null || true
    lsof -ti:3000 | xargs kill -9 2>/dev/null || true
    
    print_msg "All services stopped" "$GREEN"
}

# Docker setup
docker_setup() {
    print_header "Docker Setup"
    
    if [ ! -f "docker-compose.yml" ]; then
        print_msg "docker-compose.yml not found!" "$RED"
        exit 1
    fi
    
    print_msg "Building and starting containers..." "$BLUE"
    docker-compose up --build -d
    
    print_msg "\nServices started:" "$GREEN"
    print_msg "  Frontend: http://localhost:3000" "$GREEN"
    print_msg "  Backend:  http://localhost:8080" "$GREEN"
    print_msg "  Swagger:  http://localhost:8080/swagger-ui.html" "$GREEN"
}

# Show help
show_help() {
    echo "VAM Portal - Quick Start Script"
    echo ""
    echo "Usage: ./quickstart.sh [command]"
    echo ""
    echo "Commands:"
    echo "  check       Check prerequisites"
    echo "  setup       Full setup (database + backend + frontend)"
    echo "  setup-db    Setup database only"
    echo "  setup-be    Setup backend only"
    echo "  setup-fe    Setup frontend only"
    echo "  start       Start backend and frontend"
    echo "  start-be    Start backend only"
    echo "  start-fe    Start frontend only"
    echo "  stop        Stop all services"
    echo "  docker      Start with Docker Compose"
    echo "  docker-down Stop Docker containers"
    echo "  help        Show this help"
    echo ""
}

# Main script
case "${1:-help}" in
    check)
        check_prerequisites
        ;;
    setup)
        check_prerequisites
        setup_database
        setup_backend
        setup_frontend
        print_header "Setup Complete!"
        print_msg "Run './quickstart.sh start' to start the application" "$GREEN"
        ;;
    setup-db)
        setup_database
        ;;
    setup-be)
        setup_backend
        ;;
    setup-fe)
        setup_frontend
        ;;
    start)
        run_backend
        sleep 5
        run_frontend
        print_header "Application Started"
        print_msg "Frontend: http://localhost:3000" "$GREEN"
        print_msg "Backend:  http://localhost:8080" "$GREEN"
        print_msg "Swagger:  http://localhost:8080/swagger-ui.html" "$GREEN"
        print_msg "\nRun './quickstart.sh stop' to stop all services" "$YELLOW"
        ;;
    start-be)
        run_backend
        ;;
    start-fe)
        run_frontend
        ;;
    stop)
        stop_services
        ;;
    docker)
        docker_setup
        ;;
    docker-down)
        docker-compose down
        print_msg "Docker containers stopped" "$GREEN"
        ;;
    help|*)
        show_help
        ;;
esac
