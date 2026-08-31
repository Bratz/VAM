@echo off
setlocal enabledelayedexpansion

REM ==========================================
REM VAM Portal - Quick Start Script (Windows)
REM ==========================================

title VAM Portal Setup

:menu
cls
echo ==========================================
echo   VAM Portal - Quick Start
echo ==========================================
echo.
echo   1. Check Prerequisites
echo   2. Full Setup (Database + Backend + Frontend)
echo   3. Setup Database Only
echo   4. Setup Backend Only
echo   5. Setup Frontend Only
echo   6. Start All Services
echo   7. Start Backend Only
echo   8. Start Frontend Only
echo   9. Stop All Services
echo   10. Docker Setup
echo   0. Exit
echo.
set /p choice="Enter your choice: "

if "%choice%"=="1" goto check_prereq
if "%choice%"=="2" goto full_setup
if "%choice%"=="3" goto setup_db
if "%choice%"=="4" goto setup_backend
if "%choice%"=="5" goto setup_frontend
if "%choice%"=="6" goto start_all
if "%choice%"=="7" goto start_backend
if "%choice%"=="8" goto start_frontend
if "%choice%"=="9" goto stop_all
if "%choice%"=="10" goto docker_setup
if "%choice%"=="0" exit
goto menu

:check_prereq
cls
echo ==========================================
echo   Checking Prerequisites
echo ==========================================
echo.

REM Check Java
java -version >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Java found
    java -version 2>&1 | findstr /i "version"
) else (
    echo [ERROR] Java not found. Please install JDK 21+
)
echo.

REM Check Maven
mvn -version >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Maven found
) else (
    echo [ERROR] Maven not found. Please install Maven 3.9+
)
echo.

REM Check Node.js
node -v >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Node.js found
    node -v
) else (
    echo [ERROR] Node.js not found. Please install Node.js 18+
)
echo.

REM Check npm
npm -v >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] npm found
) else (
    echo [ERROR] npm not found
)
echo.

REM Check PostgreSQL
psql --version >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] PostgreSQL found
) else (
    echo [WARNING] PostgreSQL CLI not in PATH ^(may still work if installed^)
)
echo.

REM Check Docker
docker --version >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Docker found ^(optional^)
) else (
    echo [INFO] Docker not found ^(optional^)
)
echo.

pause
goto menu

:setup_db
cls
echo ==========================================
echo   Setting Up Database
echo ==========================================
echo.

set /p DB_HOST="Enter PostgreSQL host [localhost]: "
if "%DB_HOST%"=="" set DB_HOST=localhost

set /p DB_PORT="Enter PostgreSQL port [5432]: "
if "%DB_PORT%"=="" set DB_PORT=5432

set /p DB_USER="Enter PostgreSQL superuser [postgres]: "
if "%DB_USER%"=="" set DB_USER=postgres

set /p DB_PASS="Enter PostgreSQL superuser password: "

echo.
echo Creating database and user...
echo.

REM Create SQL file
echo -- VAM Database Setup > setup_db.sql
echo DO $$ >> setup_db.sql
echo BEGIN >> setup_db.sql
echo     IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vam_user') THEN >> setup_db.sql
echo         CREATE USER vam_user WITH PASSWORD 'vam_secure_password_123'; >> setup_db.sql
echo     END IF; >> setup_db.sql
echo END >> setup_db.sql
echo $$; >> setup_db.sql

set PGPASSWORD=%DB_PASS%
psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -f setup_db.sql

psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -c "CREATE DATABASE vam_db OWNER vam_user;" 2>nul
psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -c "GRANT ALL PRIVILEGES ON DATABASE vam_db TO vam_user;"

psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -d vam_db -c "CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";"
psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -d vam_db -c "CREATE EXTENSION IF NOT EXISTS \"pgcrypto\";"

del setup_db.sql

echo.
echo Database setup complete!
echo.
pause
goto menu

:setup_backend
cls
echo ==========================================
echo   Setting Up Backend
echo ==========================================
echo.

cd backend

REM Create migration directory
if not exist "src\main\resources\db\migration" mkdir src\main\resources\db\migration

REM Copy migrations
if exist "..\database\migrations" (
    echo Copying database migrations...
    copy /Y ..\database\migrations\*.sql src\main\resources\db\migration\ >nul 2>&1
)

echo Building backend ^(this may take a few minutes^)...
echo.
call mvn clean install -DskipTests

cd ..

echo.
echo Backend build complete!
echo.
pause
goto menu

:setup_frontend
cls
echo ==========================================
echo   Setting Up Frontend
echo ==========================================
echo.

cd frontend

echo Installing dependencies...
call npm install

REM Create .env if not exists
if not exist ".env" (
    echo Creating .env file...
    (
        echo VITE_API_BASE_URL=http://localhost:8080
        echo VITE_API_TIMEOUT=30000
        echo VITE_ENABLE_MOCK_DATA=true
        echo VITE_ENABLE_DEBUG=true
        echo VITE_APP_NAME=VAM Portal
        echo VITE_APP_VERSION=1.0.0
    ) > .env
)

cd ..

echo.
echo Frontend setup complete!
echo.
pause
goto menu

:full_setup
call :check_prereq_silent
call :setup_db
call :setup_backend
call :setup_frontend
echo.
echo ==========================================
echo   Full Setup Complete!
echo ==========================================
echo.
echo Run option 6 to start the application.
echo.
pause
goto menu

:check_prereq_silent
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java not found!
    pause
    goto menu
)
mvn -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Maven not found!
    pause
    goto menu
)
node -v >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Node.js not found!
    pause
    goto menu
)
exit /b 0

:start_all
cls
echo ==========================================
echo   Starting All Services
echo ==========================================
echo.

echo Starting Backend...
start "VAM Backend" cmd /c "cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev"

echo Waiting for backend to start...
timeout /t 10 /nobreak >nul

echo Starting Frontend...
start "VAM Frontend" cmd /c "cd frontend && npm run dev"

echo.
echo ==========================================
echo   Services Started!
echo ==========================================
echo.
echo   Frontend: http://localhost:3000
echo   Backend:  http://localhost:8080
echo   Swagger:  http://localhost:8080/swagger-ui.html
echo.
echo   Close the terminal windows to stop services.
echo.
pause
goto menu

:start_backend
cls
echo Starting Backend...
start "VAM Backend" cmd /c "cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
echo.
echo Backend starting on http://localhost:8080
echo.
pause
goto menu

:start_frontend
cls
echo Starting Frontend...
start "VAM Frontend" cmd /c "cd frontend && npm run dev"
echo.
echo Frontend starting on http://localhost:3000
echo.
pause
goto menu

:stop_all
cls
echo ==========================================
echo   Stopping All Services
echo ==========================================
echo.

echo Stopping processes on port 8080...
for /f "tokens=5" %%a in ('netstat -aon ^| find ":8080" ^| find "LISTENING"') do (
    taskkill /F /PID %%a >nul 2>&1
)

echo Stopping processes on port 3000...
for /f "tokens=5" %%a in ('netstat -aon ^| find ":3000" ^| find "LISTENING"') do (
    taskkill /F /PID %%a >nul 2>&1
)

echo.
echo All services stopped.
echo.
pause
goto menu

:docker_setup
cls
echo ==========================================
echo   Docker Setup
echo ==========================================
echo.

docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Docker not found!
    pause
    goto menu
)

if not exist "docker-compose.yml" (
    echo [ERROR] docker-compose.yml not found!
    pause
    goto menu
)

echo Building and starting containers...
docker-compose up --build -d

echo.
echo ==========================================
echo   Docker Services Started!
echo ==========================================
echo.
echo   Frontend: http://localhost:3000
echo   Backend:  http://localhost:8080
echo.
echo   Run 'docker-compose down' to stop.
echo.
pause
goto menu
