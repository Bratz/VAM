# VAM Portal - Project Setup Guide

## Complete Step-by-Step Setup Instructions

This guide will help you set up the Virtual Account Management (VAM) Portal from scratch on your local machine.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Clone/Download Project](#2-clonedownload-project)
3. [Database Setup (PostgreSQL)](#3-database-setup-postgresql)
4. [Backend Setup (Spring Boot)](#4-backend-setup-spring-boot)
5. [Frontend Setup (React + Vite)](#5-frontend-setup-react--vite)
6. [Running the Application](#6-running-the-application)
7. [Docker Setup (Alternative)](#7-docker-setup-alternative)
8. [Configuration Reference](#8-configuration-reference)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. Prerequisites

### Required Software

| Software | Version | Download Link |
|----------|---------|---------------|
| **Java JDK** | 21+ | [Adoptium](https://adoptium.net/) or [Oracle](https://www.oracle.com/java/technologies/downloads/) |
| **Maven** | 3.9+ | [Apache Maven](https://maven.apache.org/download.cgi) |
| **Node.js** | 20+ LTS | [Node.js](https://nodejs.org/) |
| **PostgreSQL** | 15+ | [PostgreSQL](https://www.postgresql.org/download/) |
| **Redis** | 7+ | [Redis](https://redis.io/download/) (optional for caching) |
| **Git** | Latest | [Git](https://git-scm.com/downloads) |

### Verify Installation

Open a terminal and run:

```bash
# Check Java
java -version
# Expected: openjdk version "21.x.x" or similar

# Check Maven
mvn -version
# Expected: Apache Maven 3.9.x

# Check Node.js
node -version
# Expected: v20.x.x or higher

# Check npm
npm -version
# Expected: 10.x.x or higher

# Check PostgreSQL
psql --version
# Expected: psql (PostgreSQL) 15.x or higher

# Check Git
git --version
```

### IDE Recommendations

- **Backend**: IntelliJ IDEA (Community or Ultimate) or VS Code with Java extensions
- **Frontend**: VS Code with the following extensions:
  - ESLint
  - Prettier
  - Tailwind CSS IntelliSense
  - TypeScript Vue Plugin (Volar) - for better TS support

---

## 2. Clone/Download Project

### Option A: From Downloaded Files

If you've downloaded the project files from Claude:

```bash
# Create project directory
mkdir -p ~/projects/vam-portal
cd ~/projects/vam-portal

# Copy downloaded files here
# Your structure should look like:
# vam-portal/
# ├── backend/
# ├── frontend/
# ├── database/
# └── docs/
```

### Option B: Initialize Git Repository

```bash
cd ~/projects/vam-portal

# Initialize git
git init

# Create .gitignore
cat > .gitignore << 'EOF'
# Java
target/
*.class
*.jar
*.war
*.ear
.idea/
*.iml

# Node
node_modules/
dist/
.npm
.eslintcache

# Environment
.env
.env.local
.env.*.local
*.log

# OS
.DS_Store
Thumbs.db

# IDE
.vscode/
*.swp
*.swo
EOF

# Initial commit
git add .
git commit -m "Initial VAM Portal setup"
```

---

## 3. Database Setup (PostgreSQL)

### 3.1 Start PostgreSQL Service

**Windows:**
```powershell
# If installed via installer, it should auto-start
# Or use Services app to start "postgresql-x64-15"
```

**macOS:**
```bash
# If installed via Homebrew
brew services start postgresql@15
```

**Linux (Ubuntu/Debian):**
```bash
sudo systemctl start postgresql
sudo systemctl enable postgresql
```

### 3.2 Create Database and User

```bash
# Connect to PostgreSQL as superuser
# Windows: Use pgAdmin or:
psql -U postgres

# macOS/Linux:
sudo -u postgres psql
```

Run these SQL commands:

```sql
-- Create the database user
CREATE USER vam_user WITH PASSWORD 'vam_secure_password_123';

-- Create the database
CREATE DATABASE vam_db OWNER vam_user;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE vam_db TO vam_user;

-- Connect to the new database
\c vam_db

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Grant schema privileges
GRANT ALL ON SCHEMA public TO vam_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO vam_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO vam_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON FUNCTIONS TO vam_user;

-- Exit
\q
```

### 3.3 Verify Database Connection

```bash
# Test connection with new user
psql -h localhost -U vam_user -d vam_db

# If successful, you should see:
# vam_db=>

# Exit with \q
```

### 3.4 Apply Database Migrations (Manual - Optional)

The migrations will run automatically via Flyway when starting the backend. But if you want to apply manually:

```bash
cd database/migrations

# Apply in order
psql -h localhost -U vam_user -d vam_db -f V2__critical_architecture_improvements.sql
psql -h localhost -U vam_user -d vam_db -f V3__ecommerce_viban_and_ihb.sql
psql -h localhost -U vam_user -d vam_db -f V4__unified_architecture.sql
```

---

## 4. Backend Setup (Spring Boot)

### 4.1 Create Application Configuration

Create the application configuration file:

```bash
cd backend/src/main/resources
```

Create `application.yml`:

```yaml
# application.yml
spring:
  application:
    name: vam-service
  
  # Database Configuration
  datasource:
    url: jdbc:postgresql://localhost:5432/vam_db
    username: vam_user
    password: vam_secure_password_123
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      idle-timeout: 300000
      connection-timeout: 20000
      max-lifetime: 1200000
  
  # JPA Configuration
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.PostgreSQLDialect
        default_schema: public
    open-in-view: false
  
  # Flyway Migration
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    validate-on-migrate: true
  
  # Redis Cache (optional - comment out if not using)
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000
  
  # Jackson JSON
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      fail-on-unknown-properties: false
    default-property-inclusion: non_null

# Server Configuration
server:
  port: 8080
  servlet:
    context-path: /api
  error:
    include-message: always
    include-binding-errors: always

# Security Configuration
security:
  jwt:
    secret-key: your-256-bit-secret-key-here-make-it-long-and-random-for-production
    expiration: 86400000  # 24 hours
    refresh-expiration: 604800000  # 7 days

# BaNCS Integration (Mock/Stub for development)
bancs:
  api:
    base-url: http://localhost:8090/bancs-stub
    timeout: 30000
    retry-attempts: 3
  sync:
    enabled: false
    cron: "0 */5 * * * *"

# API Documentation
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method

# Actuator Endpoints
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when_authorized

# Logging
logging:
  level:
    root: INFO
    com.bank.vam: DEBUG
    org.springframework.security: INFO
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE

---
# Development Profile
spring:
  config:
    activate:
      on-profile: dev
  
  # H2 Console for quick testing (optional)
  h2:
    console:
      enabled: false

# Disable security for development (optional)
security:
  enabled: false

---
# Production Profile
spring:
  config:
    activate:
      on-profile: prod
  
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}

security:
  jwt:
    secret-key: ${JWT_SECRET_KEY}
```

### 4.2 Create Flyway Migration Directory

```bash
# Create migration directory structure
mkdir -p backend/src/main/resources/db/migration

# Copy migration files
cp database/migrations/*.sql backend/src/main/resources/db/migration/

# Rename if needed (Flyway expects V1__, V2__, etc.)
# Files should be:
# V2__critical_architecture_improvements.sql
# V3__ecommerce_viban_and_ihb.sql
# V4__unified_architecture.sql
```

### 4.3 Build Backend

```bash
cd backend

# Clean and build (skip tests for initial setup)
mvn clean install -DskipTests

# Or with tests
mvn clean install
```

**Expected Output:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: X.XXX s
```

### 4.4 Run Backend

```bash
# Development mode
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Or run the JAR directly
java -jar target/vam-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

**Expected Output:**
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.5)

... Tomcat started on port(s): 8080 (http)
... Started VamServiceApplication in X.XXX seconds
```

### 4.5 Verify Backend

```bash
# Health check
curl http://localhost:8080/api/actuator/health

# Expected: {"status":"UP"}

# Swagger UI (open in browser)
# http://localhost:8080/api/swagger-ui.html
```

---

## 5. Frontend Setup (React + Vite)

### 5.1 Install Dependencies

```bash
cd frontend

# Install all dependencies
npm install

# If you get peer dependency warnings, you can use:
npm install --legacy-peer-deps
```

### 5.2 Environment Configuration

Create `.env` file in frontend directory:

```bash
cat > .env << 'EOF'
# API Configuration
VITE_API_BASE_URL=http://localhost:8080/api
VITE_API_TIMEOUT=30000

# Feature Flags
VITE_ENABLE_MOCK_DATA=true
VITE_ENABLE_DEBUG=true

# App Info
VITE_APP_NAME=VAM Portal
VITE_APP_VERSION=1.0.0
EOF
```

Create `.env.production`:

```bash
cat > .env.production << 'EOF'
VITE_API_BASE_URL=/api
VITE_API_TIMEOUT=30000
VITE_ENABLE_MOCK_DATA=false
VITE_ENABLE_DEBUG=false
VITE_APP_NAME=VAM Portal
VITE_APP_VERSION=1.0.0
EOF
```

### 5.3 Update Vite Config (if needed)

Check `vite.config.ts`:

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
})
```

### 5.4 Import Design System CSS

Update `src/main.tsx`:

```typescript
import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './design-system/variables.css'  // Add this line
import './styles/index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
```

### 5.5 Run Frontend Development Server

```bash
npm run dev
```

**Expected Output:**
```
  VITE v5.1.4  ready in XXX ms

  ➜  Local:   http://localhost:3000/
  ➜  Network: use --host to expose
  ➜  press h + enter to show help
```

### 5.6 Build for Production

```bash
npm run build

# Preview production build
npm run preview
```

---

## 6. Running the Application

### Quick Start (All Services)

**Terminal 1 - Database (if not running as service):**
```bash
# PostgreSQL should already be running as a service
# Verify with:
psql -h localhost -U vam_user -d vam_db -c "SELECT 1"
```

**Terminal 2 - Backend:**
```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Terminal 3 - Frontend:**
```bash
cd frontend
npm run dev
```

### Access Points

| Service | URL |
|---------|-----|
| **Frontend** | http://localhost:3000 |
| **Backend API** | http://localhost:8080/api |
| **Swagger UI** | http://localhost:8080/api/swagger-ui.html |
| **API Docs** | http://localhost:8080/api/api-docs |
| **Health Check** | http://localhost:8080/api/actuator/health |

---

## 7. Docker Setup (Alternative)

### 7.1 Create Docker Compose File

Create `docker-compose.yml` in project root:

```yaml
version: '3.8'

services:
  # PostgreSQL Database
  postgres:
    image: postgres:15-alpine
    container_name: vam-postgres
    environment:
      POSTGRES_DB: vam_db
      POSTGRES_USER: vam_user
      POSTGRES_PASSWORD: vam_secure_password_123
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./database/migrations:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U vam_user -d vam_db"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Redis Cache
  redis:
    image: redis:7-alpine
    container_name: vam-redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Backend Service
  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: vam-backend
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DATABASE_URL: jdbc:postgresql://postgres:5432/vam_db
      DATABASE_USERNAME: vam_user
      DATABASE_PASSWORD: vam_secure_password_123
      REDIS_HOST: redis
      REDIS_PORT: 6379
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy

  # Frontend Service
  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    container_name: vam-frontend
    ports:
      - "3000:80"
    depends_on:
      - backend

volumes:
  postgres_data:
  redis_data:
```

### 7.2 Create Backend Dockerfile

Create `backend/Dockerfile`:

```dockerfile
# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 7.3 Create Frontend Dockerfile

Create `frontend/Dockerfile`:

```dockerfile
# Build stage
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Runtime stage
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

### 7.4 Create Nginx Config

Create `frontend/nginx.conf`:

```nginx
server {
    listen 80;
    server_name localhost;
    root /usr/share/nginx/html;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml;

    # API proxy
    location /api {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }

    # SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

### 7.5 Run with Docker Compose

```bash
# Build and start all services
docker-compose up --build

# Run in background
docker-compose up -d --build

# View logs
docker-compose logs -f

# Stop all services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

---

## 8. Configuration Reference

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DATABASE_URL` | PostgreSQL connection URL | `jdbc:postgresql://localhost:5432/vam_db` |
| `DATABASE_USERNAME` | Database user | `vam_user` |
| `DATABASE_PASSWORD` | Database password | - |
| `JWT_SECRET_KEY` | JWT signing key (256-bit) | - |
| `REDIS_HOST` | Redis server host | `localhost` |
| `REDIS_PORT` | Redis server port | `6379` |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `dev` |

### Port Reference

| Service | Port | Description |
|---------|------|-------------|
| Frontend | 3000 | Vite dev server |
| Backend | 8080 | Spring Boot API |
| PostgreSQL | 5432 | Database |
| Redis | 6379 | Cache |

---

## 9. Troubleshooting

### Database Connection Issues

```bash
# Check PostgreSQL is running
pg_isready -h localhost -p 5432

# Check connection
psql -h localhost -U vam_user -d vam_db

# Common fixes:
# 1. Check pg_hba.conf allows local connections
# 2. Ensure PostgreSQL service is running
# 3. Verify credentials
```

### Backend Startup Issues

```bash
# Check Java version
java -version

# Clear Maven cache and rebuild
mvn clean install -U

# Run with debug logging
mvn spring-boot:run -Dspring-boot.run.profiles=dev -X

# Common fixes:
# 1. Ensure database is accessible
# 2. Check application.yml syntax
# 3. Verify all migrations ran successfully
```

### Frontend Build Issues

```bash
# Clear node_modules and reinstall
rm -rf node_modules package-lock.json
npm install

# Clear Vite cache
rm -rf node_modules/.vite

# Common fixes:
# 1. Use correct Node version (20+)
# 2. Check for TypeScript errors
# 3. Verify import paths
```

### Flyway Migration Errors

```bash
# Check migration status
mvn flyway:info

# Repair if needed (use with caution)
mvn flyway:repair

# Common fixes:
# 1. Ensure migrations are in correct order (V2, V3, V4...)
# 2. Check SQL syntax
# 3. Verify database user has required permissions
```

### CORS Issues

If frontend can't reach backend:

1. Check Vite proxy config
2. Verify backend CORS settings
3. Check browser console for errors

Add CORS config to backend if needed:

```java
@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                    .allowedOrigins("http://localhost:3000")
                    .allowedMethods("*")
                    .allowedHeaders("*");
            }
        };
    }
}
```

---

## Quick Reference Commands

```bash
# === Database ===
psql -h localhost -U vam_user -d vam_db    # Connect to database

# === Backend ===
cd backend
mvn clean install                           # Build
mvn spring-boot:run                         # Run
mvn test                                    # Run tests
mvn flyway:info                             # Check migrations

# === Frontend ===
cd frontend
npm install                                 # Install dependencies
npm run dev                                 # Development server
npm run build                               # Production build
npm run lint                                # Run linter

# === Docker ===
docker-compose up -d                        # Start all services
docker-compose down                         # Stop all services
docker-compose logs -f backend              # View backend logs
```

---

## Next Steps After Setup

1. **Create Test Data**: Run seed scripts or use Swagger UI to create sample data
2. **Configure BaNCS Integration**: Set up mock BaNCS service or connect to sandbox
3. **Set Up Authentication**: Configure OAuth2/JWT provider
4. **Enable Redis Caching**: Start Redis and uncomment cache config
5. **Review API Documentation**: Access Swagger UI at `/api/swagger-ui.html`

---

**Need Help?** Check the `/docs` folder for:
- `ARCHITECTURAL_ANALYSIS.md` - System architecture overview
- `REDESIGNED_ARCHITECTURE.md` - Unified programs model
- `TRANSACTION_MODEL_SINGLE_ENTRY.md` - Transaction handling
- `COMPREHENSIVE_VAM_ANALYSIS.md` - Use case mapping
