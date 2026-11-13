# Running HOR Ingestion Console Locally

## Prerequisites

1. **PostgreSQL** running locally on default port 5432.
2. **hor-auth** running on port 8091:
   ```bash
   cd ../../hor-auth/services/auth-service
   ./gradlew.bat bootRun
   ```
3. **verifone-ingestion-service** running on port 8092:
   ```bash
   cd ../verifone-ingestion-service
   ./gradlew.bat bootRun
   ```
4. **gilbarco-ingestion-service** running on port 8093:
   ```bash
   cd ../gilbarco-ingestion-service
   ./gradlew.bat bootRun
   ```

## Starting the Ingestion Console

In this folder (`stores-ingestion/ingestion-console`):

```bash
# First time setup:
npm install

# Start Vite dev server:
npm run dev
```

The application is served at **http://localhost:5182/admin/**.

> **Important:** Always open `http://localhost:5182/admin/` (not `127.0.0.1:5182`), as `localhost` matches the Origin allowed by the backend services.

## Authentication & Networking

- Log in using your `PLATFORM_ADMIN` credentials located in `%USERPROFILE%\.hor-stores\secrets.properties`. Type them manually; never commit credentials.
- Only accounts with the `PLATFORM_ADMIN` role have access to the ingestion console. Other account roles (such as `HOR_STAFF` or `CLIENT_USER`) will be presented with a **No Access** screen.
- Session tokens are stored in HttpOnly cookies (`hor_access`, `hor_refresh`) and proxied same-origin by Vite:
  - `/api/auth` → `http://127.0.0.1:8091`
  - `/api/ingestion/gilbarco` → `http://127.0.0.1:8093`
  - `/api/ingestion` → `http://127.0.0.1:8092`
- Active vendor routes live under `/admin/verifone/...` and `/admin/gilbarco/...`. Old links redirect to `/admin/verifone/...`.

## Verification Checklist

### Verifone Acceptance Checklist
- [ ] `/admin/` redirects to `/admin/login` when not authenticated.
- [ ] Submitting bad credentials shows `BAD_CREDENTIALS` error banner and clears the password field.
- [ ] Successful platform admin login lands on `/admin/verifone/stores` with admin name badge in header.
- [ ] `/admin/history?storeId=1` redirects to login with `return` query parameter and returns to `/admin/verifone/history?storeId=1` after login.
- [ ] Non-admin accounts (HOR_STAFF, CLIENT_USER) see NoAccess screen with working Log out.
- [ ] Log out invalidates session and navigates to login.
- [ ] Sync stores updates master stores from `hor-auth` and displays summary statistics.
- [ ] Fetch calendar displays available S3 days, loaded days, and disables future dates.
- [ ] Ingestion fetch details poll live until finished, displaying rows and 9 load checks.
- [ ] Departments displays current names, history of names, and lottery classifications.
- [ ] Stopping backend service shows clean `BACKEND_DOWN` error banner without app crash.
- [ ] Dark theme toggle persists `hor-theme` in localStorage.
- [ ] Responsive design functions without horizontal scroll at 375px viewport width.
- [ ] `npm run lint` and `npm run build` pass with zero warnings or errors.

### Gilbarco Acceptance Checklist (Plan §10)
- [ ] `/admin/` navigates to last vendor (first time Verifone); old `/admin/history?storeId=1` lands on `/admin/verifone/history?storeId=1`.
- [ ] Switching vendor reloads the store list; no Verifone store appears under Gilbarco and vice versa.
- [ ] Gilbarco **Sync stores** shows the summary; Wolf `800_NY-13208` listed with timezone `America/New_York`.
- [ ] Gilbarco Fetch, October 2026, PJR: **Check S3** marks 2026-10-09 … (2026-10-11 absent); future days disabled.
- [ ] Fetch 2026-10-15 REFUSE: detail shows Queued/Running, then **Loaded**, zip key, 974 files, row counts, 6 checks all passed.
- [ ] Same day REFUSE again → **Refused**; REPLACE (confirm modal) → Loaded again.
- [ ] Clicking Fetch twice fast → second shows `FETCH_RUNNING` message.
- [ ] History (Gilbarco) lists the fetches with File type PJR; filters by store, dates, file type; paging works.
- [ ] Departments (Gilbarco, Wolf) shows 5 LOTTO SCRATCH OFF / 6 LOTTO PAID OUT / 57 LOTTO ONLINE with lottery classes.
- [ ] Stop gilbarco-ingestion-service → Gilbarco pages show `BACKEND_DOWN` naming 8093; Verifone pages still work.
- [ ] 375 px wide: header (with switcher) wraps, no sideways scroll; dark theme fine.
- [ ] `npm run lint` and `npm run build`: zero warnings.
