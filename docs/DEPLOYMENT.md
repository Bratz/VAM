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
  **Regional reality check**: free A1 capacity is scarce in popular regions;
  you may only be offered the AMD `VM.Standard.E2.1.Micro` (1 GB RAM). That
  works too — see *"1GB fallback"* below before deploying.
- Image: **Ubuntu 22.04/24.04**. Add your SSH public key.
- Networking: let the wizard **create a new VCN with a public subnet** — that
  auto-creates the Internet Gateway and the route table sending `0.0.0.0/0`
  through it. If you build the VCN by hand, verify all three exist (public
  subnet, internet gateway, route rule) — without the route rule the VM has
  no path to the internet and every later step fails silently.
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
git clone https://github.com/Bratz/VAM.git ~/VAM && cd ~/VAM/deploy/oci
echo 'VAM_DB_PASSWORD=<pick-a-strong-password>' > .env   # untracked; compose reads it
sudo docker compose up -d --build
sudo docker compose logs -f backend   # watch for "[seed] Dump loaded." then the Spring banner
```
(The password lives only in `deploy/oci/.env` on the VM — the tracked compose
file stays clean, so auto-deploy `git pull` never conflicts.)

### 1GB fallback (AMD E2.1.Micro regions)
This Spring context wants ~1 GB by itself, so on a 1 GB VM two things are
mandatory:
1. **Swap** (absorbs startup spikes; slow but survives):
   ```bash
   sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
   sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   ```
2. **Cap the JVM** — uncomment the `JAVA_OPTS` line in
   `deploy/oci/docker-compose.yml` (640m heap, serial GC).

Expect multi-minute startups and sluggish first requests. Alternative:
Always Free allows **two** E2.1.Micro VMs — put Postgres on one and the
backend on the other (change `SPRING_DATASOURCE_URL` to the DB VM's private
IP, open 5432 between them in the security list). And keep retrying for A1
capacity; you can rebuild there in minutes since everything is in the repo.

First boot: the container seeds Postgres from the repo's dump
(`SEED_ON_START=true`, idempotent — later restarts skip it).

### 4. Verify
```bash
curl http://<PUBLIC_IP>:8053/actuator/health
```
(`{"status":"DOWN"}` is acceptable here — a subcomponent like Redis reports
down but the API serves; `/api/v1/...` endpoints are what matter.)

---

## Part 1.5 — MCP gateway (optional)

Puts the Aperture MCP server (see [`docs/mcp-architecture.md`](mcp-architecture.md))
behind a real OAuth 2.1 trust boundary reachable from ChatGPT/Claude, instead
of only `localhost:9443` on a dev machine. Entirely opt-in — skip this
section and the VM behaves exactly as Part 1 describes.

### 1. Open port 9443 — same two firewalls as step 2 above
**a) Cloud:** VCN → Security List → Add Ingress Rule: source `0.0.0.0/0`,
protocol TCP, destination port `9443`.

**b) VM:**
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 9443 -j ACCEPT
sudo netfilter-persistent save
```

### 2. Configure and deploy
```bash
cd ~/VAM/deploy/oci
echo 'VAM_MCP_ENABLED=true' >> .env
echo "GATEWAY_PUBLIC_BASE_URL=http://<PUBLIC_IP>:9443" >> .env
sudo docker compose --profile mcp up -d --build
```
`GATEWAY_PUBLIC_BASE_URL` becomes the OAuth issuer and redirect URI — it
**must** be the address a browser/ChatGPT/Claude actually reaches, not
`localhost`. `VAM_MCP_ENABLED=true` flips the backend's `/mcp` endpoint on
*and* requires every call to carry a gateway-signed context (see
`deploy/oci/docker-compose.yml`'s comments) — the backend and gateway
containers pick this up on their next restart, so re-run the `up -d --build`
above if you only edited `.env`.

The routine `docker compose up -d --build` that `deploy-oci.yml` runs on
every push to `main` does **not** include `--profile mcp`, so a normal
auto-deploy never starts or restarts the gateway unexpectedly — re-run the
profiled command by hand (or SSH in) whenever the gateway itself needs to
pick up a change.

### 3. Verify
```bash
curl http://<PUBLIC_IP>:9443/.well-known/oauth-authorization-server
curl http://<PUBLIC_IP>:9443/context-jwks
```
Both should return JSON (metadata, then a JWK Set) — if either times out,
recheck both firewalls; if either 404s, recheck `--profile mcp` was included.
Full OAuth-code+PKCE-to-tools/call walkthrough: see `docs/mcp-architecture.md`.

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

## Part 3 — Auto-deploy (push-to-deploy, self-hosted edition)

Managed platforms redeploy on push; a bare VM serves the old build until
someone SSHes in. [`.github/workflows/deploy-oci.yml`](../.github/workflows/deploy-oci.yml)
rebuilds that feature: every push to `main` SSHes into the VM, `git pull`s,
and `docker compose up -d --build`s.

One-time setup — repo → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `VM_HOST` | the VM's public IP |
| `VM_USER` | `ubuntu` |
| `VM_SSH_KEY` | the **private** key matching the VM's authorized key (generate a dedicated deploy keypair; don't reuse your personal key) |

The Vercel side already auto-deploys on push natively.

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
