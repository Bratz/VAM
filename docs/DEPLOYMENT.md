# Deployment — Oracle Cloud (backend + DB) + Vercel (frontend)

Replaces the Render setup. Architecture:

```
Browser ──▶ Vercel (static frontend, free)
              │  server-side rewrite of /api/* (no CORS, no mixed content)
              ▼
        OCI Always-Free VM (Ampere A1, Ubuntu)
        └─ docker compose: backend (self-seeding) + Postgres 16
```

The frontend build uses `VITE_API_BASE_URL=/api/v1` (same-origin); Vercel's
edge proxies `/api/*` to the VM, so the backend needs **no domain, no TLS
cert, and no CORS config** to start with.

---

## Part 1 — Oracle Cloud VM

### 1. Create the instance
- OCI Console → Compute → Instances → Create.
- Shape: **Ampere A1 Flex** (Always Free: up to 4 OCPU / 24 GB — take at
  least 1 OCPU / 6 GB; this app wants ~1 GB heap plus Postgres).
- Image: **Ubuntu 22.04/24.04 (aarch64)**. Add your SSH public key.
- Note the **public IP**.

### 2. Open port 8053 — BOTH firewalls (the classic OCI gotcha)
Traffic must pass the *cloud* firewall **and** the *VM's own* iptables —
Oracle's Ubuntu images ship with restrictive iptables rules, so opening only
the Security List silently doesn't work.

**a) Cloud:** VCN → your subnet's **Security List** → Add Ingress Rule:
source `0.0.0.0/0`, protocol TCP, destination port `8053`.

**b) VM:** SSH in, then:
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8053 -j ACCEPT
sudo netfilter-persistent save
```

### 3. Install Docker & deploy
```bash
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
git clone https://github.com/Bratz/VAM.git && cd VAM/deploy/oci
# Edit docker-compose.yml: replace BOTH occurrences of
# 'change-me-strong-password' with a real password.
sudo docker compose up -d --build
sudo docker compose logs -f backend   # watch for "[seed] Dump loaded." then the Spring banner
```

First boot: the container seeds Postgres from the repo's dump
(`SEED_ON_START=true`, idempotent — later restarts skip it).

### 4. Verify
```bash
curl http://<PUBLIC_IP>:8053/actuator/health
```
(`{"status":"DOWN"}` is acceptable here — a subcomponent like Redis reports
down but the API serves; `/api/v1/...` endpoints are what matter.)

---

## Part 2 — Vercel frontend

1. Edit [`frontend/vercel.json`](../frontend/vercel.json): replace
   `REPLACE_WITH_OCI_PUBLIC_IP` with the VM's public IP. Commit + push.
   (vercel.json cannot use env vars — the IP is hardcoded by design.)
2. vercel.com → Add New Project → import `Bratz/VAM`.
   - **Root Directory: `frontend`** (critical — the repo root is not the app).
   - Framework preset: Vite (auto-detected). Build command/output: defaults.
   - Environment variable: `VITE_API_BASE_URL` = `/api/v1`
     (the production build hard-fails without it — by design, see
     `frontend/src/services/api.ts`).
3. Deploy. The app is served at `https://<project>.vercel.app`, and every
   `/api/*` call is proxied server-side to the OCI VM.

---

## Later hardening (optional, in order of value)
1. **Domain + TLS on the VM**: point a (sub)domain at the IP, add a Caddy
   service (`caddy reverse_proxy backend:8053`) — auto-HTTPS. Then the Vercel
   rewrite destination becomes `https://api.yourdomain.com/...`.
2. **Close 8053 to the world**: once fronted by Caddy on 443, drop the 8053
   ingress rules.
3. **Credentials**: the compose file's DB password is VM-local; the demo
   fallbacks in `application.yml` / `.env.example` should be blanked if the
   GitHub repo is public.
4. **Backups**: `docker compose exec db pg_dump -U vam_user vam_db | gzip >
   backup.sql.gz` on a cron; the pgdata volume survives compose restarts but
   not instance deletion.

## Gotchas log
- **ARM works out of the box**: temurin/maven/postgres images are multi-arch;
  no Dockerfile changes were needed for aarch64.
- **Always-Free capacity**: A1 instances in popular regions intermittently
  show "out of capacity" — retry, try another availability domain, or create
  the account in a less busy home region.
- **Vercel rewrite to plain http is fine**: the proxy runs server-side on
  Vercel's edge, so the browser only ever sees https — no mixed-content
  blocking, no CORS.
