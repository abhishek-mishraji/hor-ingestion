# Deploying stores-ingestion to the VM

Target: the Oracle Ubuntu VM that already runs **auth-service** (`auth.handsoffretail.com`).
Everything for ingestion is served on **`https://ingestion.handsoffretail.com`**.

## 1. What runs where

```
Browser ──https──▶ nginx  ingestion.handsoffretail.com
                    ├── /            → 302 to /admin/
                    ├── /admin/      → ingestion-console build (static files)
                    ├── /api/auth/   → auth-service               127.0.0.1:8091  (already running)
                    ├── /api/ingestion/gilbarco/ → gilbarco-ingestion  127.0.0.1:8093
                    └── /api/ingestion/          → verifone-ingestion  127.0.0.1:8092

verifone-ingestion ──▶ verifone_db (owns, Flyway) + auth_db (read-only, store sync) + S3 hor-stores-data
gilbarco-ingestion ──▶ gilbarco_db (owns, Flyway) + auth_db (read-only, store sync) + S3 hor-stores-data
both               ──▶ http://127.0.0.1:8091/api/auth/.well-known/jwks.json (verify login tokens)
```

| Item | Value |
|---|---|
| Clone on VM | `/opt/hor/store-automation/hor-ingestion` (repo `hor-ingestion`) |
| verifone service | folder `verifone-ingestion-service/`, port **8092**, unit `verifone-ingestion` |
| gilbarco service | folder `gilbarco-ingestion-service/`, port **8093**, unit `gilbarco-ingestion` |
| Console | `ingestion-console/dist`, served at `/admin/` |
| Runs as | `ubuntu` (same as auth-service) |
| Settings | `.env` in each service folder (git-ignored) |
| DB login | `auth_svc` (same user and password as auth-service) |

## 2. Dependencies (order matters)

1. **PostgreSQL**: `verifone_db`, `gilbarco_db`, `auth_db` exist, owned by `auth_svc`. (Done.)
2. **auth-service running**: ingestion verifies every login token against its JWKS.
3. **auth-client library** in the VM's local Maven repo (`~/.m2`): both services build against
   `com.handsoffretail:auth-client:0.1.0`, published from `hor-auth/libs/auth-client`.
4. **AWS keys** with read access to S3 bucket `hor-stores-data` (us-east-1).
5. **DNS**: `ingestion.handsoffretail.com` A record → VM public IP.

## 3. Before you start (on your laptop)

Push these repo changes first. The VM builds from GitHub.

- **hor-auth**
  - `libs/auth-client`: wildcard origins (`https://*.handsoffretail.com`).
  - `web/auth-console/.env.production`: admins land on `https://ingestion.handsoffretail.com/admin/` after login.
- **hor-ingestion**
  - Allowed origins configurable via `HOR_ALLOWED_ORIGINS`.
  - `infra/` (nginx and systemd files) and this document.

## 4. Steps on the VM

### Step 1: Pre-flight checks
```bash
dig +short ingestion.handsoffretail.com; curl -s ifconfig.me; echo    # same IP twice
sudo ss -ltnp | grep -E ':(8092|8093)\b' || echo "8092/8093 free"
free -h                                                               # need ~1.2 GB available for 2 more JVMs
systemctl is-active auth-service                                      # active
sudo -u postgres psql -c "\l" | grep -E "verifone_db|gilbarco_db"     # both, owner auth_svc
```
If memory is short, add swap. See the auth guide, "Step 5: swap".

### Step 2: Update auth (new auth-client + console redirect)
```bash
cd /opt/hor/centralized-auth/hor-auth && git pull
(cd libs/auth-client && bash gradlew publishToMavenLocal --no-daemon)
ls ~/.m2/repository/com/handsoffretail/auth-client/0.1.0/            # jar is there
(cd services/auth-service && bash gradlew clean bootJar --no-daemon) && sudo systemctl restart auth-service
(cd web/auth-console && npm ci && npm run build)
```
Check `.../auth-service/.env` allowed origins covers ingestion: `https://*.handsoffretail.com`.

### Step 3: Clone the ingestion repo
The repo is private. A GitHub deploy key works for **one** repo only, so make a second key for `hor-ingestion`:
```bash
ssh-keygen -t ed25519 -f ~/.ssh/hor_ingestion_deploy -N "" -C "oracle-vm-hor-ingestion"
cat ~/.ssh/hor_ingestion_deploy.pub
```
GitHub → **hor-ingestion → Settings → Deploy keys → Add** (read-only). Then:
```bash
cat >> ~/.ssh/config <<'EOF'
Host github-hor-ingestion
    HostName github.com
    User git
    IdentityFile ~/.ssh/hor_ingestion_deploy
    IdentitiesOnly yes
EOF
sudo mkdir -p /opt/hor/store-automation/hor-ingestion && sudo chown ubuntu:ubuntu /opt/hor/store-automation/hor-ingestion
git clone github-hor-ingestion:abhishek-mishraji/hor-ingestion.git /opt/hor/store-automation/hor-ingestion
```
(If you cloned hor-auth over HTTPS with a token instead, clone this the same way.)

### Step 4: `.env` for each service
Create **both** files, with the same content:
```bash
nano /opt/hor/store-automation/hor-ingestion/verifone-ingestion-service/.env
nano /opt/hor/store-automation/hor-ingestion/gilbarco-ingestion-service/.env
```
```properties
HOR_DB_PASSWORD=SAME_AUTH_SVC_PASSWORD_AS_AUTH_SERVICE
HOR_AWS_ACCESS_KEY_ID=YOUR_KEY_ID
HOR_AWS_SECRET_ACCESS_KEY=YOUR_SECRET
HOR_STORAGE_MODE=s3
HOR_ALLOWED_ORIGINS=https://ingestion.handsoffretail.com
```
Optional, defaults shown: `HOR_DB_HOST=localhost`, `HOR_DB_PORT=5432`, `HOR_DB_USERNAME=auth_svc`.
Do **not** copy your laptop `.env`: it has a Windows `HOR_TESTDATA_ROOT` and dev values.
```bash
chmod 600 /opt/hor/store-automation/hor-ingestion/*-ingestion-service/.env
```
Production AWS keys should belong to an IAM user that can only **read** `hor-stores-data`, not your personal or admin keys.

### Step 5: Build
```bash
cd /opt/hor/store-automation/hor-ingestion
(cd verifone-ingestion-service && bash gradlew clean bootJar --no-daemon)
(cd gilbarco-ingestion-service && bash gradlew clean bootJar --no-daemon)
(cd ingestion-console && npm ci && npm run build)
ls -lh */build/libs/*.jar ingestion-console/dist/index.html
```

### Step 6: Services
```bash
sudo cp /opt/hor/store-automation/hor-ingestion/infra/systemd/verifone-ingestion.service /etc/systemd/system/
sudo cp /opt/hor/store-automation/hor-ingestion/infra/systemd/gilbarco-ingestion.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now verifone-ingestion
sudo journalctl -u verifone-ingestion -f       # wait: "Successfully applied N migrations" + "Started", then Ctrl+C
sudo systemctl enable --now gilbarco-ingestion
sudo journalctl -u gilbarco-ingestion -f       # same, then Ctrl+C
```
Local checks (expect `401`, which means it is up but you are not logged in):
```bash
curl -s -o /dev/null -w "verifone %{http_code}\n" http://127.0.0.1:8092/api/ingestion/verifone/stores
curl -s -o /dev/null -w "gilbarco %{http_code}\n" http://127.0.0.1:8093/api/ingestion/gilbarco/stores
```

### Step 7: nginx + HTTPS
nginx (`www-data`) must be able to read the console build:
```bash
sudo chmod o+x /opt/hor /opt/hor/store-automation/hor-ingestion /opt/hor/store-automation/hor-ingestion/ingestion-console
sudo chmod -R o+rX /opt/hor/store-automation/hor-ingestion/ingestion-console/dist
sudo -u www-data test -r /opt/hor/store-automation/hor-ingestion/ingestion-console/dist/index.html && echo OK
```
```bash
sudo cp -a /etc/nginx /etc/nginx.backup-$(date +%F-%H%M)
sudo cp /opt/hor/store-automation/hor-ingestion/infra/nginx/ingestion.conf /etc/nginx/sites-available/ingestion.handsoffretail.com
sudo ln -s /etc/nginx/sites-available/ingestion.handsoffretail.com /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d ingestion.handsoffretail.com        # choose Redirect if asked
```

### Step 8: Verify end to end
```bash
curl -sI https://ingestion.handsoffretail.com/ | head -1                         # 302 -> /admin/
curl -s https://ingestion.handsoffretail.com/admin/ | grep -oE 'src="[^"]+"'     # /admin/assets/...js
curl -s https://ingestion.handsoffretail.com/api/auth/.well-known/jwks.json | head -c 40; echo
curl -s -o /dev/null -w "%{http_code}\n" https://ingestion.handsoffretail.com/api/ingestion/verifone/stores   # 401
curl -s -o /dev/null -w "%{http_code}\n" https://ingestion.handsoffretail.com/api/ingestion/gilbarco/stores   # 401
```
In the browser:
1. Log in at `https://auth.handsoffretail.com`. It lands on `https://ingestion.handsoffretail.com/admin/`.
2. In the console, run **Stores → Sync** for Verifone and Gilbarco. This copies the stores from `auth_db`.
3. Fetch one business day and confirm the rows appear.

## 5. Updating later
```bash
cd /opt/hor/store-automation/hor-ingestion && git pull
(cd verifone-ingestion-service && bash gradlew clean bootJar --no-daemon) && sudo systemctl restart verifone-ingestion
(cd gilbarco-ingestion-service && bash gradlew clean bootJar --no-daemon) && sudo systemctl restart gilbarco-ingestion
(cd ingestion-console && npm ci && npm run build)
```
If auth-client changed in hor-auth: `publishToMavenLocal` again (Step 2) **before** rebuilding these.

## 6. Rollback
- **Service:** `git checkout <previous-commit>`, rebuild, restart. Flyway never wipes data (`clean-disabled=true`).
- **nginx:** `sudo rm /etc/nginx/sites-enabled/ingestion.handsoffretail.com && sudo systemctl reload nginx`.
- **Full nginx restore:** copy back the `/etc/nginx.backup-*` folder.

## 7. Troubleshooting
| Symptom | Cause / fix |
|---|---|
| Build: `Could not find com.handsoffretail:auth-client:0.1.0` | Step 2 `publishToMavenLocal` not done (as user `ubuntu`) |
| Start: `password authentication failed` | `HOR_DB_PASSWORD` must equal the auth_svc password |
| Start: S3/AWS credentials error | `HOR_AWS_*` missing or wrong in `.env` |
| API `401` even when logged in | auth-service down, or cookie domain not `handsoffretail.com` in auth `.env` |
| API `403 CSRF_REJECTED` | `HOR_ALLOWED_ORIGINS` must be `https://ingestion.handsoffretail.com` |
| Browser `502` on `/api/ingestion/...` | that service is not running: `systemctl status verifone-ingestion` |
| `/admin/` 404 / blank | console not built, or nginx cannot read `dist` (Step 7 chmod) |
| `nginx -t`: `zero size shared memory zone "hor_auth_login"` | the auth site (which defines it) is not enabled |
| Logs | `sudo journalctl -u verifone-ingestion -f`, `sudo journalctl -u gilbarco-ingestion -f` |
