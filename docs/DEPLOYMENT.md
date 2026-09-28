# Deployment — Oracle Cloud (backend + DB) + Vercel (frontend)

Replaces the Render setup. Architecture:

```
Browser ──▶ Vercel (static frontend, free)
              │  server-side rewrite of /api/* (no CORS, no mixed content)
              ▼
        OCI Always-Free VM (Ampere A1, Ubuntu)
        └─ docker compose: Caddy (real HTTPS, :80/:443) ──▶ backend :8053
                           + Postgres 16
```

The frontend build uses `VITE_API_BASE_URL=/api/v1` (same-origin) and Vercel's
edge proxies `/api/*` to the VM. That rewrite now targets the VM's **HTTPS**
hostname via Caddy, not plain `http://<ip>:8053` — see the gotchas log for why
the plain-HTTP version had to go. So TLS (free, automatic) and a CORS allow-list
(`APERTURE_CORS_ALLOWED_ORIGINS` in the compose file) are both required, not
optional extras.

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

### 2. Open ports 80, 443 and 8053 — BOTH firewalls (the classic OCI gotcha)
Traffic must pass the *cloud* firewall **and** the *VM's own* iptables —
Oracle's Ubuntu images ship with restrictive iptables rules, so opening only
the Security List silently doesn't work.

80 and 443 are for Caddy (Let's Encrypt's HTTP-01 challenge, then HTTPS
itself) — the path the deployed frontend's `/api/*` calls actually take. 8053
is the backend directly, useful for health checks and `curl` debugging; it can
be closed later (see *Later hardening*).

**a) Cloud:** VCN → your subnet's **Security List** → Add Ingress Rule, source
`0.0.0.0/0`, protocol TCP, destination port `80`; repeat for `443` and `8053`.

**b) VM:** SSH in, then:
```bash
for p in 80 443 8053; do sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport $p -j ACCEPT; done
sudo netfilter-persistent save
```

### 3. Install Docker & deploy
```bash
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
# Cap the systemd journal — it defaults to 10% of the disk (~4.5GB here) and
# never shrinks. One of the four things that filled this 45GB root volume; the
# others are container logs (capped in the compose file), BuildKit cache
# (pruned by the deploy workflow) and old images.
echo 'SystemMaxUse=500M' | sudo tee -a /etc/systemd/journald.conf && sudo systemctl restart systemd-journald
git clone https://github.com/Bratz/VAM.git ~/VAM && cd ~/VAM/deploy/oci
echo 'VAM_DB_PASSWORD=<pick-a-strong-password>' > .env   # untracked; compose reads it
sudo docker compose --profile mcp --profile defectfix --profile fileingest up -d --build
sudo docker compose logs -f backend   # watch for "[seed] Dump loaded." then the Spring banner
```
(The password lives only in `deploy/oci/.env` on the VM — the tracked compose
file stays clean, so auto-deploy `git pull` never conflicts.)

**The `--profile` flags are not optional**, even though the profiled services
themselves are. `caddy` is declared `profiles: ["mcp","defectfix","fileingest"]`,
so a plain `docker compose up -d --build` starts Postgres and the backend but
**never starts Caddy** — nothing listens on 443, and every `/api/*` call from the
deployed frontend fails. Drop a profile only when you also stop needing what it
fronts. The profiled services will start without their secrets configured (see
Part 1.5 and the compose file's comments) and simply fail their own outbound
calls, deliberately, rather than taking the stack down.

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
of only `localhost:9443` on a dev machine. Opt-in: Part 1's command starts the
gateway *container*, but the backend's `/mcp` endpoint stays off (`VAM_MCP_ENABLED`
defaults to false) and the gateway's OAuth issuer points at localhost until you
do the two steps below. Skip this section and the VM serves the app exactly as
Part 1 describes, with an idle gateway container alongside.

**Real HTTPS is required**, not optional — Claude's connector flow is a
browser redirect from `https://claude.ai` and won't complete against plain
HTTP, and OpenAI's docs state MCP servers "must be hosted behind a stable
HTTPS endpoint." `deploy/oci/docker-compose.yml`'s `caddy` service handles
this: automatic Let's Encrypt HTTPS in front of the gateway, using
[sslip.io](https://sslip.io)'s free wildcard DNS so no domain purchase or
registration is needed — `<ip-with-dashes>.sslip.io` (e.g.
`161-33-9-182.sslip.io` for that literal IP) just resolves to the IP itself.
The one downside: if the VM's IP ever changes, this hostname changes with
it — worth a real domain later if the deployment becomes long-lived.

### 1. Ports 80 and 443 — already open if you followed step 2 above
Step 2 opens them, since `/api/*` needs Caddy too; if you opened only 8053 back
when this section was the only reason for 80/443, go back and add them. The gateway's own port 9443 no longer
needs a public firewall rule at all — it's not published outside the
compose network anymore (Caddy is the sole public entry point); leaving an
old 9443 rule in place is harmless, since nothing will be listening on the
host's 9443 to answer it.

**a) Cloud:** VCN → Security List → Add Ingress Rule for each: source
`0.0.0.0/0`, protocol TCP, destination port `80`; repeat for `443`.

**b) VM:**
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

### 2. Configure and deploy
```bash
cd ~/VAM/deploy/oci
echo 'VAM_MCP_ENABLED=true' >> .env
echo "GATEWAY_PUBLIC_BASE_URL=https://<ip-with-dashes>.sslip.io" >> .env
sudo docker compose --profile mcp up -d --build
```
`GATEWAY_PUBLIC_BASE_URL` becomes the OAuth issuer and redirect URI — it
**must** be the real HTTPS hostname a browser/ChatGPT/Claude actually
reaches. `VAM_MCP_ENABLED=true` flips the backend's `/mcp` endpoint on *and*
requires every call to carry a gateway-signed context (see
`deploy/oci/docker-compose.yml`'s comments) — the backend and gateway
containers pick this up on their next restart, so re-run the `up -d --build`
above if you only edited `.env`.

`deploy-oci.yml` passes `--profile mcp --profile defectfix --profile fileingest`
on every push to `main`, so the gateway (and Caddy) are rebuilt and restarted by
the routine auto-deploy along with everything else. That is deliberate: Compose
neither rebuilds nor stops a running container whose profile is inactive for the
invocation, so a plain `up` would leave these serving their old image forever
even after `git pull` fetched new code. If you stop using a profile on the VM,
remove its flag from the workflow too.

### 3. Verify
```bash
curl https://<ip-with-dashes>.sslip.io/.well-known/oauth-authorization-server
curl https://<ip-with-dashes>.sslip.io/.well-known/oauth-protected-resource
curl https://<ip-with-dashes>.sslip.io/context-jwks
```
All three should return JSON over a real HTTPS connection (check for a valid
cert, not just a 200) — if any times out, recheck both firewalls; a
certificate error usually means Caddy hasn't finished obtaining one yet
(`docker compose logs -f caddy` shows progress); a 404 means recheck
`--profile mcp` was included. Full OAuth-code+PKCE-to-tools/call walkthrough:
see `docs/mcp-architecture.md`.

---

## Part 2 — Vercel frontend

1. Edit [`frontend/vercel.json`](../frontend/vercel.json): point the `/api/:path*`
   rewrite at the VM's **HTTPS** hostname — `https://<ip-with-dashes>.sslip.io/api/:path*`,
   the same host Caddy serves (currently `161-33-9-182.sslip.io`). Commit + push.
   (vercel.json cannot use env vars — the host is hardcoded by design, which is
   why a new VM IP means editing it. NOT `http://<ip>:8053`: see the gotchas log.)
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
1. ~~**TLS on the VM**~~ — **done**: the `caddy` service terminates real
   Let's Encrypt HTTPS for `/api/*`, the webhooks and the MCP gateway (see
   `deploy/oci/Caddyfile`). Remaining upgrade: a **real domain** instead of
   sslip.io, so a new VM IP doesn't mean editing the Caddyfile, `vercel.json`,
   `APERTURE_CORS_ALLOWED_ORIGINS`, `GATEWAY_PUBLIC_BASE_URL` and the `VM_HOST`
   secret — five hardcoded places today.
2. **Close 8053 to the world**: Caddy already fronts `/api/*` on 443, so the
   8053 ingress rule is only a debugging convenience — drop it once you don't
   need direct `curl` access.
3. **Credentials**: the compose file's DB password is VM-local; the demo
   fallbacks in `application.yml` / `.env.example` should be blanked if the
   GitHub repo is public.
4. **Backups**: `docker compose exec db pg_dump -U vam_user vam_db | gzip >
   backup.sql.gz` on a cron; the pgdata volume survives compose restarts but
   not instance deletion.

## Gotchas log
- **`/` fills up and the build dies with "no space left on device"**: four
  compounding causes. `docker system df` does NOT count container log files, so
  the real hog is usually `/var/lib/docker/containers/*/*-json.log` (the compose
  file now caps these at 10m x 3 per service, but only for *recreated*
  containers — truncate the existing ones with
  `sudo sh -c 'for f in /var/lib/docker/containers/*/*-json.log; do : > "$f"; done'`).
  Then BuildKit cache (`sudo docker builder prune -af`), the journal (above),
  and old images. Never `docker system prune --volumes` — that destroys `pgdata`
  and Caddy's Let's Encrypt cert.
- **ARM works out of the box**: temurin/maven/postgres images are multi-arch;
  no Dockerfile changes were needed for aarch64.
- **Always-Free capacity**: A1 instances in popular regions intermittently
  show "out of capacity" — retry, try another availability domain, or create
  the account in a less busy home region.
- **Vercel rewrite to plain http is NOT fine** (superseded — this entry used to
  say the opposite). The browser never sees mixed content, true, but Vercel's
  rewrite proxy silently **403s any non-GET method** to an `http://` destination:
  confirmed live, every POST in the app failed this way, not just uploads. Fixed
  by fronting the backend with Caddy's real cert too (`handle /api/*` in the
  Caddyfile) and pointing the rewrite at the HTTPS host.
- **"Invalid CORS request" on every POST from the deployed frontend**: not a
  Vercel or TLS problem despite appearances — `WebConfig`'s CorsFilter defaults
  to localhost-only dev origins. Both the Vercel origin and the VM's own HTTPS
  host must be listed in `APERTURE_CORS_ALLOWED_ORIGINS` in the compose file.
