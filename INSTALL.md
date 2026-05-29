# vam-portal — Installation Guide

A Spring Boot \+ React/TypeScript application implementing virtual account management. This guide takes you from a clean machine to a running local instance in about 30 minutes.

This is the canonical install document for this repository. Any older `SETUP_GUIDE.md` you may find is retained only as a redirect to here.

---

## 1\. What you'll have at the end

- Backend API on `http://localhost:8080`  
- Frontend on `http://localhost:5173`  
- PostgreSQL with the full database (schema \+ data) restored from a single dump file  
- Demo credentials that let you click through the portal

## 2\. Prerequisites

Install in this order. Versions matter — newer is not always better for Spring Boot 3.2.

| Tool | Version | Why |
| :---- | :---- | :---- |
| **JDK** | 17 (Temurin, Oracle, or Corretto) | Spring Boot 3.2 requires 17; do not use 21 unless you have time to debug |
| **Maven** | 3.8.x or 3.9.x | Backend build |
| **Node.js** | 20.x LTS (not 18, not 22\) | Vite 5 plugin compatibility |
| **npm** | 10.x (bundled with Node 20\) | — |
| **PostgreSQL** | 16.x (15.x will also work; older versions may not) | Server \+ `psql` \+ `pg_restore` client tools |
| **Git** | any recent | — |

Verify each:

**Windows (PowerShell):**

java \-version    \# 17.x

mvn \-version

node \--version   \# v20.x

npm \--version

psql \--version   \# 16.x or 15.x

git \--version

**macOS / Linux:**

java \-version

mvn \-version

node \--version

npm \--version

psql \--version

git \--version

**Windows users:** if a command above reports "not recognized," the tool isn't on your PATH. PostgreSQL's client tools live at `C:\Program Files\PostgreSQL\16\bin` — add that to PATH, or call binaries with their full path. Note that `psql` and `pg_restore` versions must match (or exceed) the PostgreSQL server version the dump was produced on (16.x). If your client tools are 14.x, restore will fail in confusing ways.

## 3\. Clone the repository

git clone https://github.com/Bratz/VAM.git vam-portal

cd vam-portal

You should see top-level directories: `backend\`, `frontend\`, `database\`, `docs\`, plus `README.md`, this `INSTALL.md`, and `.env.example`.

## 4\. Restore the database

The repository ships with a single full PostgreSQL dump containing schema, extensions, sequences, and seed data:

database\\vam\_db.dump      (custom-format binary dump)

Restore steps follow. **Windows commands first; Unix equivalents below each block.**

### 4.1 Create the database user and empty database

**Windows (PowerShell), assuming a PostgreSQL "postgres" superuser exists:**

psql \-U postgres \-h localhost \-c "CREATE USER vam\_user WITH PASSWORD 'vam\_local\_password';"

psql \-U postgres \-h localhost \-c "ALTER USER vam\_user CREATEDB;"

psql \-U postgres \-h localhost \-c "CREATE DATABASE vam\_db OWNER vam\_user;"

psql \-U postgres \-h localhost \-d vam\_db \-c "GRANT ALL ON SCHEMA public TO vam\_user;"

psql \-U postgres \-h localhost \-d vam\_db \-c 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp";'

psql \-U postgres \-h localhost \-d vam\_db \-c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"

The single quotes around the `uuid-ossp` line are needed because the extension name contains a hyphen and must be quoted in SQL. PowerShell treats single quotes as literal string delimiters; this passes through correctly. **If you are using `cmd.exe`, switch to PowerShell** — `cmd.exe` doesn't strip single quotes, and PostgreSQL will reject the extension name as malformed. As a fallback, connect with `psql -U postgres -d vam_db` interactively and paste the `CREATE EXTENSION` line directly into the SQL prompt.

**Linux / macOS:**

sudo \-u postgres psql \<\<'SQL'

CREATE USER vam\_user WITH PASSWORD 'vam\_local\_password';

ALTER USER vam\_user CREATEDB;

CREATE DATABASE vam\_db OWNER vam\_user;

\\c vam\_db

GRANT ALL ON SCHEMA public TO vam\_user;

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE EXTENSION IF NOT EXISTS pgcrypto;

SQL

The password `vam_local_password` is for local development only. Never use it anywhere reachable from a network.

Verify the connection:

**Windows:**

$env:PGPASSWORD \= "vam\_local\_password"

psql \-h localhost \-U vam\_user \-d vam\_db \-c "SELECT current\_database(), current\_user;"

**Linux / macOS:**

PGPASSWORD=vam\_local\_password psql \-h localhost \-U vam\_user \-d vam\_db \-c "SELECT current\_database(), current\_user;"

You should see `vam_db | vam_user`.

### 4.2 Restore the dump

**Windows (PowerShell):**

$env:PGPASSWORD \= "vam\_local\_password"

pg\_restore \-h localhost \-U vam\_user \-d vam\_db \--no-owner \--no-acl \--jobs=4 \--verbose database\\vam\_db.dump

**Linux / macOS:**

PGPASSWORD=vam\_local\_password pg\_restore \\

  \-h localhost \-U vam\_user \-d vam\_db \\

  \--no-owner \--no-acl \--jobs=4 \--verbose \\

  database/vam\_db.dump

Restore time on a laptop: 1-3 minutes. Warnings about *"must be owner of extension"* are expected and harmless — they appear because the dump references the superuser as extension owner and we're restoring as `vam_user`. The `--no-owner` flag tells `pg_restore` to ignore these.

### 4.3 Verify the restore

**Windows:**

psql \-h localhost \-U vam\_user \-d vam\_db \-c "SELECT count(\*) AS table\_count FROM information\_schema.tables WHERE table\_schema='public';"

psql \-h localhost \-U vam\_user \-d vam\_db \-c "SELECT count(\*) AS corporates FROM corporates;"

psql \-h localhost \-U vam\_user \-d vam\_db \-c "SELECT count(\*) AS virtual\_accounts FROM virtual\_accounts;"

psql \-h localhost \-U vam\_user \-d vam\_db \-c "SELECT count(\*) AS physical\_accounts FROM physical\_accounts;"

psql \-h localhost \-U vam\_user \-d vam\_db \-c "SELECT count(\*) AS sweep\_rules FROM sweep\_rules;"

Remove-Item Env:PGPASSWORD

**Linux / macOS:**

PGPASSWORD=vam\_local\_password psql \-h localhost \-U vam\_user \-d vam\_db \<\<'SQL'

SELECT count(\*) AS table\_count FROM information\_schema.tables WHERE table\_schema \= 'public';

SELECT count(\*) AS corporates FROM corporates;

SELECT count(\*) AS virtual\_accounts FROM virtual\_accounts;

SELECT count(\*) AS physical\_accounts FROM physical\_accounts;

SELECT count(\*) AS sweep\_rules FROM sweep\_rules;

SQL

You should see approximately 65 tables and non-zero row counts in each domain table. If `table_count` is 0, the restore didn't run; if it's significantly under 65, the restore aborted partway through — check the verbose output from §4.2 for the first error.

### 4.4 About the data

All data shipped in this dump is illustrative. "Brato Group" is a fictional corporate; bank names, BICs, account numbers, balances, and counterparty references are synthetic or scrubbed. Do not interpret any record in this database as relating to a real entity, customer, or transaction.

If you find what looks like real data, stop using the database and report it to the person who shared the repo with you. Don't try to clean it yourself — request a replacement dump.

## 5\. Configure environment variables

**Windows:**

copy .env.example .env

**Linux / macOS:**

cp .env.example .env

Open `.env` in your editor. The defaults are tuned for the local PostgreSQL setup above and should work without edits. The three values that matter:

DB\_URL=jdbc:postgresql://localhost:5432/vam\_db

DB\_USER=vam\_user

DB\_PASSWORD=vam\_local\_password

If you changed the password in §4.1, change it here too.

If the frontend has its own env template:

**Windows:**

cd frontend

if (Test-Path .env.example) { copy .env.example .env.local }

cd ..

**Linux / macOS:**

cd frontend && \[ \-f .env.example \] && cp .env.example .env.local; cd ..

The frontend `.env.local` typically only needs `VITE_API_BASE_URL=http://localhost:8080`.

## 6\. Build and run the backend

cd backend

mvn clean install \-DskipTests

mvn spring-boot:run

The first `mvn clean install` downloads \~300 MB of Maven dependencies and takes 5-10 minutes. Subsequent builds are fast.

Watch the log for `Started VamPortalApplication in N seconds`. The API is now on `http://localhost:8080`.

Smoke test in another terminal:

**Windows (PowerShell native):**

Invoke-WebRequest http://localhost:8080/actuator/health

**Linux / macOS:**

curl http://localhost:8080/actuator/health

Expected response: `{"status":"UP"}`.

Leave the backend running.

## 7\. Build and run the frontend

In a new terminal:

cd frontend

npm install

npm run dev

`npm install` takes 2-3 minutes. `npm run dev` starts Vite on `http://localhost:5173`.

Open `http://localhost:5173` in a browser. You should see the login page.

## 8\. Log in and explore

Demo credentials defined in seed data:

| Role | Username | Password |
| :---- | :---- | :---- |
| Treasury admin | `aarav.mehta@brato.example` | `LocalDemo123!` |
| Operations | `priya.shah@brato.example` | `LocalDemo123!` |

**If these don't work**, the seed credentials may have been rotated. Find the actual seeded users with:

$env:PGPASSWORD \= "vam\_local\_password"

psql \-h localhost \-U vam\_user \-d vam\_db \-c "SELECT username, email, status FROM users LIMIT 10;"

(Adjust the column names if your `users` table uses different ones.) Passwords are bcrypt-hashed in the database — plaintext cannot be recovered from the dump. Contact the person who shared the repo for current credentials.

Click around: Dashboard, Multi-Bank Liquidity, Cash Concentration, Notional Pooling, and other pages should render with synthetic data. If a page loads but shows an empty state, that's expected for some entity-scoped views — try switching the corporate or program in the selector at the top.

## 9\. Common installation problems

**Port 8080 already in use.** Something else is listening.

**Windows:**

netstat \-ano | findstr :8080

\# kill it via Task Manager using the PID from the last column

Or change the backend port in `backend\src\main\resources\application.yml`:

server:

  port: 8090

Then update the frontend's `.env.local`: `VITE_API_BASE_URL=http://localhost:8090`.

**Database connection refused.** PostgreSQL isn't running or isn't bound to `localhost:5432`.

**Windows:**

Get-Service postgresql\*

Start-Service postgresql-x64-16     \# adjust the suffix for your installed version

**Linux / macOS:**

sudo systemctl status postgresql    \# Linux

brew services list | grep postgres  \# macOS

**pg\_restore version mismatch.** If the dump was produced on PostgreSQL 16 and you're restoring with `pg_restore` from 14 or 15, restore fails or produces silently inconsistent results. Check with `pg_restore --version` — the major version must match or exceed the dump source. Upgrade PostgreSQL client tools or use a different machine.

**pg\_restore reports `role "postgres" does not exist`.** The dump references a role that doesn't exist on your machine. The `--no-owner` flag should suppress this, but if it leaks through, add `--role=vam_user`:

pg\_restore \--no-owner \--no-acl \--role=vam\_user \-h localhost \-U vam\_user \-d vam\_db database\\vam\_db.dump

**Maven build fails on Lombok or annotation processing.** Nearly always a JDK version mismatch.

java \-version

$env:JAVA\_HOME       \# Windows

\# echo $JAVA\_HOME    \# Linux/macOS

`java -version` must report 17.x. `$env:JAVA_HOME` (or `$JAVA_HOME`) should point at a JDK 17 install. If you have multiple JDKs:

\# Windows example

$env:JAVA\_HOME \= "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.12.7-hotspot"

mvn clean install \-DskipTests

\# macOS

export JAVA\_HOME=$(/usr/libexec/java\_home \-v 17\)

\# Linux

export JAVA\_HOME=/usr/lib/jvm/java-17-openjdk-amd64

**`npm install` fails with peer-dependency errors.** Confirm Node 20.x (not 18, not 22). Switch with `nvm` (Linux/macOS) or `nvm-windows`:

nvm install 20 && nvm use 20

Then clear and reinstall:

**Windows:**

Remove-Item \-Recurse \-Force node\_modules, package-lock.json \-ErrorAction SilentlyContinue

npm install

**Linux / macOS:**

rm \-rf node\_modules package-lock.json

npm install

**Frontend loads but every API call returns 401 or CORS error.** The backend CORS configuration doesn't include `http://localhost:5173`. Open `backend\src\main\resources\application.yml`, find the `cors.allowed-origins` block, ensure `http://localhost:5173` is listed, restart the backend.

**Restore failed partway through, some tables populated, others empty.** Don't try to patch. Drop and recreate cleanly:

psql \-U postgres \-h localhost \-c "DROP DATABASE vam\_db;"

psql \-U postgres \-h localhost \-c "CREATE DATABASE vam\_db OWNER vam\_user;"

\# then redo §4.1 (extensions) and §4.2 (restore)

**The dump complains about missing extensions even though §4.1 created them.** Order of declaration in the dump may not match the order extensions appear. Restore as superuser instead:

psql \-U postgres \-h localhost \-c "DROP DATABASE vam\_db;"

psql \-U postgres \-h localhost \-c "CREATE DATABASE vam\_db OWNER vam\_user;"

pg\_restore \-U postgres \-h localhost \-d vam\_db \--no-owner \--no-acl database\\vam\_db.dump

psql \-U postgres \-h localhost \-d vam\_db \-c "REASSIGN OWNED BY postgres TO vam\_user;"

The `REASSIGN` step transfers object ownership to `vam_user` so the application can read/write normally.

**`psql` commands fail with quote errors on Windows `cmd.exe`.** `cmd.exe` treats single quotes as literal characters, not string delimiters. Switch to PowerShell. If a command in this guide still doesn't work as written, fall back to an interactive `psql` session — connect with `psql -U vam_user -d vam_db` and paste the SQL body without shell quoting.

## 10\. Stopping and restarting

Stop the backend with `Ctrl+C` in its terminal. Stop the frontend the same way. PostgreSQL keeps running; your data persists.

To start again later:

\# terminal 1

cd vam-portal\\backend; mvn spring-boot:run

\# terminal 2

cd vam-portal\\frontend; npm run dev

No need to re-restore the database.

## 11\. Resetting to a clean state

If your local DB gets into a confused state and you want to restart from the shipped dump:

psql \-U postgres \-h localhost \-c "DROP DATABASE IF EXISTS vam\_db;"

psql \-U postgres \-h localhost \-c "CREATE DATABASE vam\_db OWNER vam\_user;"

\# then redo §4.1 (extensions) and §4.2 (restore)

This wipes any changes you've made locally.

## 12\. What's in the repo (orientation)

vam-portal/

├── backend/                 Spring Boot 3.2 / Java 17 API

│   └── src/main/java/com/bank/vam/      ... domain packages

├── frontend/                React 18 / TypeScript / Vite / Tailwind

│   └── src/

│       ├── pages/                       page-level routes

│       ├── components/                  feature components

│       └── services/api.ts              the API client (one large file by design)

├── database/

│   ├── vam\_db.dump                      full PostgreSQL custom-format dump

│   └── README.md                        notes on how the dump was produced

├── docs/                    architectural documents

├── tasks/                   build prompts and design notes

├── .env.example             environment template

├── README.md                project overview

└── INSTALL.md               this file

## 13\. Where to start reading the code

If you're trying to understand the system rather than just run it, read in this order:

1. **`README.md`** for the product concept.  
2. **`docs/COMPREHENSIVE_VAM_ANALYSIS.md`** for the architectural model. The three-layer Physical Account → Shadow VA → Sweep Rule premise is the foundation of everything else.  
3. **The database itself.** Open `psql` and explore the schema:  
     
   \\dt public.\*  
     
   \\d virtual\_accounts  
     
   \\d physical\_accounts  
     
   \\d sweep\_rules  
     
   \\d notional\_pools  
     
   The schema *is* the design. Read the `COMMENT ON COLUMN` and `COMMENT ON TABLE` lines in particular — they encode design intent.  
     
4. **`frontend/src/pages/DashboardPage.tsx`** and **`MultiBankLiquidityPage.tsx`** for the user-facing model.  
5. **`frontend/src/services/api.ts`** for the API surface. It's deliberately one large file.  
6. Backend Java packages mirror the schema's domain boundaries. Find the controller that backs a page, follow it down through service to repository.

## 14\. Getting help

This repository is distributed for file-sharing purposes; there is no support channel attached to it. If something is broken, check `git log` for recent changes, then contact the person who shared the repo with you.  
