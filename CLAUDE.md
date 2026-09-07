# CLAUDE.md

Guidance for Claude Code when working in the `vam-portal` repository.

---

## Workflow Orchestration

### 1. Plan Mode Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately — don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One tack per subagent for focused execution

### 3. Self-Improvement Loop
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
- Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness

### 5. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes — don't over-engineer
- Challenge your own work before presenting it

### 6. Autonomous Bug Fixing
- When given a bug report: just fix it. Don't ask for hand-holding
- Point at logs, errors, failing tests — then resolve them
- Zero context switching required from the user
- Go fix failing CI tests without being told how

---

## Task Management

1. **Plan First**: Write plan to `tasks/todo.md` with checkable items
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Mark items complete as you go
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Add review section to `tasks/todo.md`
6. **Capture Lessons**: Update `tasks/lessons.md` after corrections

---

## Core Principles

- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

---

## Frontend Aesthetics

You tend to converge toward generic, "on distribution" outputs. In frontend design, this creates what users call the "AI slop" aesthetic. Avoid this: make creative, distinctive frontends that surprise and delight. Focus on:

- **Typography**: Choose fonts that are beautiful, unique, and interesting. Avoid generic fonts like Arial and Inter; opt instead for distinctive choices that elevate the frontend's aesthetics.
- **Color & Theme**: Commit to a cohesive aesthetic. Use CSS variables for consistency. Dominant colors with sharp accents outperform timid, evenly-distributed palettes. Draw from IDE themes and cultural aesthetics for inspiration.
- **Motion**: Use animations for effects and micro-interactions. Prioritize CSS-only solutions for HTML. Use Motion library for React when available. Focus on high-impact moments: one well-orchestrated page load with staggered reveals (`animation-delay`) creates more delight than scattered micro-interactions.
- **Backgrounds**: Create atmosphere and depth rather than defaulting to solid colors. Layer CSS gradients, use geometric patterns, or add contextual effects that match the overall aesthetic.

Avoid generic AI-generated aesthetics:
- Overused font families (Inter, Roboto, Arial, system fonts)
- Clichéd color schemes (particularly purple gradients on white backgrounds)
- Predictable layouts and component patterns
- Cookie-cutter design that lacks context-specific character

Interpret creatively and make unexpected choices that feel genuinely designed for the context. Vary between light and dark themes, different fonts, different aesthetics. You still tend to converge on common choices (Space Grotesk, for example) across generations. Avoid this: it is critical that you think outside the box.

---

## Project Snapshot

**Aperture** — *"See every flow, every account, every entity."*
Corporate Digital Banking platform built on a Virtual Account Management core. Features: multi-bank liquidity, in-house bank, notional pooling & cash concentration, POBO/COBO intercompany, ISO 20022 payments, Digital Escrow, KYCC, Wallet Programs, BaNCS fallback (store-locally-sync-later), and a Treasury Copilot AI assistant.

> **Naming convention**: *Aperture* is the customer-facing product brand (UI titles, docs, marketing). *VAM* (Virtual Account Management) remains the internal codename — visible in the Java package (`com.bank.vam`), the database schema (`vam_db`), API paths (`/api/...`), configuration keys (`vam.*`), and deployment artefact names. Treat the rename as a brand/marketing change, **not** a code/schema change. Like Chromium (codebase) vs Chrome (product).

### Stack
- **Backend**: Spring Boot 3.2.5, Java 21, PostgreSQL 15, Flyway, Redis (optional), Spring Security/OAuth2
- **Frontend**: React 18 + TypeScript, Vite 5, Tailwind, React Query, Zustand, React Router 6
- **Infra**: Docker Compose (Postgres + Redis + Backend + Frontend + WireMock BaNCS stub + Adminer)

### Layout
- `backend/` — Spring Boot service (`com.bank.vam`)
- `frontend/` — Vite React app
- `database/migrations/` — Flyway SQL (V2, V3, V4)
- `database/seed/` — seed data scripts
- `docs/` — architecture references
- `vam-enhanced/` — parallel/enhanced variant of the same project (treat as alternate copy; verify before editing both)

---

## How to Start the Application (Windows / PowerShell)

### Prerequisites
- JDK 21+, Maven 3.9+, Node.js 20+ LTS, PostgreSQL 15+, (optional) Redis 7+, Git
- Verify: `java -version`, `mvn -version`, `node -v`, `psql --version`

### Option A — Guided (recommended on Windows)
Run the interactive menu from the project root:
```
quickstart.bat
```
Choices: check prereqs, set up DB/backend/frontend, start all, Docker setup.

### Option B — Manual, three terminals

**1. Database** (one-time setup as `postgres` superuser):
```sql
CREATE USER vam_user WITH PASSWORD 'vam_secure_password_123';
CREATE DATABASE vam_db OWNER vam_user;
GRANT ALL PRIVILEGES ON DATABASE vam_db TO vam_user;
\c vam_db
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
```
Flyway runs migrations automatically on backend startup.

**2. Backend** (`http://localhost:8080`):
```
cd backend
mvn clean install -DskipTests
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=C:\Temp -DVAM_MARKET_PROFILE=UK -DVAM_HOME_BANK_BIC=HBUKGB4BXXX"
```
(No `dev` profile — there is no `application-dev.yml` in the repo, so `-Dspring-boot.run.profiles=dev` was always a no-op. The command above is also what the OCI deployment's env vars mirror, just as JVM `-D` flags instead of container env vars — see `deploy/oci/docker-compose.yml`.)

> **JDK 21 on Windows / AVD / RDP — Tomcat "Unable to establish loopback connection".**
> After the Java 17→21 upgrade, on locked-down Windows sessions (Azure Virtual Desktop, RDP) where AF_UNIX sockets are disabled, the backend fails at startup with
> `java.io.IOException: Unable to establish loopback connection … UnixDomainSockets.connect0: Invalid argument`.
> JDK 21's Tomcat NIO selector opens an AF_UNIX loopback pipe under `java.io.tmpdir`; pointing that at a short, writable path fixes it — already included in the command above via `-Djdk.net.unixdomain.tmpdir=C:\Temp`. Equivalent when running the built jar:
> ```
> java -Djdk.net.unixdomain.tmpdir=C:\Temp -jar target/vam-service-1.0.0-SNAPSHOT.jar
> ```
> (Create `C:\Temp` first. Java 17 was unaffected — it used a TCP loopback pipe.)

**3. Frontend** (`http://localhost:3000`):
```
cd frontend
npm install
npm run dev
```

### Option C — Docker Compose (full stack)
```
copy .env.example .env
docker-compose up --build -d
docker-compose logs -f
docker-compose down        # stop
```
Brings up Postgres, Redis, backend, frontend, WireMock BaNCS stub, and (with `--profile tools`) Adminer.

### Access Points
| Service       | URL                                              |
|---------------|--------------------------------------------------|
| Frontend      | http://localhost:3000                            |
| Backend API   | http://localhost:8080/api                        |
| Swagger UI    | http://localhost:8080/api/swagger-ui.html        |
| Health        | http://localhost:8080/api/actuator/health        |
| Adminer (DB)  | http://localhost:8081 (Docker, `tools` profile)  |
| BaNCS stub    | http://localhost:8090 (Docker)                   |

### Common Commands
```
# Backend
mvn clean install            # build
mvn spring-boot:run          # run
mvn test                     # tests
mvn flyway:info              # migration status

# Frontend
npm run dev                  # dev server
npm run build                # production build
npm run lint                 # lint

# Docker
docker-compose up -d
docker-compose down
docker-compose logs -f backend
```

---

## Notes & Gotchas
- Server context path is `/api` — frontend talks to `http://localhost:8080/api` (Vite proxy in `vite.config.ts`).
- Flyway expects migrations in `backend/src/main/resources/db/migration/`. `quickstart.bat` copies `database/migrations/*.sql` there during backend setup.
- If you change `application.yml` profiles, mirror env vars in `.env` / `docker-compose.yml`.
- Two parallel project trees exist (`./` and `./vam-enhanced/`). Before editing, confirm which tree the user means — don't blindly edit both.
- Redis is optional in dev; comment out the Redis block in `application.yml` if not running it.
