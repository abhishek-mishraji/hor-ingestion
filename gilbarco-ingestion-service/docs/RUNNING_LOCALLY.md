# Running gilbarco-ingestion-service locally

The service fetches one business day of a Gilbarco store's PJR zip from S3 (`hor-stores-data`), cryptographically verifies it,
parses the individual transaction events, and loads them into `gilbarco_db`. It listens on `127.0.0.1:8093`. Every endpoint is for platform admins only.

## 1. Prerequisites

| What | Notes |
|---|---|
| JDK 21 | Gradle picks it up through the toolchain. |
| PostgreSQL 17 on `localhost:5432` | The same server that holds `auth_db` and `verifone_db`. `psql` is at `C:\Program Files\PostgreSQL\17\bin\psql.exe`. |
| `auth_svc` login | Created by `hor-auth/infra/postgres/01_auth_database.sql`. In development it owns `auth_db`, `verifone_db`, `gilbarco_db`, and `gilbarco_db_test`. |
| `auth-client` 0.1.0 in `~/.m2` | Published by hor-auth (`publishToMavenLocal`). Read from `mavenLocal()`. |
| hor-auth running on `127.0.0.1:8091` | Signs the `hor_access` cookie. This service validates it against hor-auth's JWKS. |
| `.env` in this folder | Already copied from `verifone-ingestion-service/.env` with active AWS S3 read-only keys and DB credentials. |

## 2. One-time setup (owner)

1. Create the empty databases as `postgres` (run once):
   ```powershell
   & "C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -f infra/postgres/01_gilbarco_databases.sql
   ```
2. Verify Wolf store (`800_NY-13208`, vendor GILBARCO) exists in `auth_db.core.stores`:
   - Store code: `800_NY-13208`
   - Store name: `Wolf`
   - Vendor: `GILBARCO`
   - S3 folder: matches the store folder in bucket `hor-stores-data`
   - Status: `ACTIVE`

## 3. Build and Test

```bash
cd stores-ingestion/gilbarco-ingestion-service
./gradlew.bat test
./gradlew.bat build
```

## 4. Run Locally

```bash
# hor-auth must already be running on 8091.
cd stores-ingestion/gilbarco-ingestion-service
./gradlew.bat bootRun
```

Startup logs end with:
`Tomcat started on port 8093` and `Started GilbarcoIngestionApplication`. Stop it with Ctrl+C.

## 5. Smoke Test (curl, Git Bash)

```bash
J=/tmp/hor.cookies; O='Origin: http://localhost:5182'; H='X-HOR-Request: 1'

# 1. Login to hor-auth (8091)
curl -s -c $J -H "$H" -H "$O" -H 'Content-Type: application/json' \
     -d '{"email":"<admin email>","password":"<admin password>"}' http://127.0.0.1:8091/api/auth/login

# 2. Sync stores from auth_db
curl -s -b $J -H "$H" -H "$O" -X POST http://127.0.0.1:8093/api/ingestion/gilbarco/stores/sync

# 3. View synced Gilbarco stores (find storeId for Wolf 800_NY-13208)
curl -s -b $J http://127.0.0.1:8093/api/ingestion/gilbarco/stores

# 4. Check available PJR days in S3 for Wolf
curl -s -b $J "http://127.0.0.1:8093/api/ingestion/gilbarco/stores/<storeId>/available-days?year=2026&month=10&fileType=PJR"

# 5. Fetch one business day (e.g. 2026-10-15; Wolf's S3 history starts 2026-10-09)
curl -s -b $J -H "$H" -H "$O" -H 'Content-Type: application/json' \
     -d '{"storeId":<storeId>,"businessDate":"2026-10-15","mode":"REFUSE","fileType":"PJR"}' http://127.0.0.1:8093/api/ingestion/gilbarco/fetches

# 6. Check fetch status until LOADED
curl -s -b $J http://127.0.0.1:8093/api/ingestion/gilbarco/fetches/<fetchRequestId>

# 7. Fetch the same day's MCM (department names + proof the PJR day is complete)
curl -s -b $J -H "$H" -H "$O" -H 'Content-Type: application/json' \
     -d '{"storeId":<storeId>,"businessDate":"2026-10-15","mode":"REFUSE","fileType":"MCM"}' http://127.0.0.1:8093/api/ingestion/gilbarco/fetches

rm -f $J
```

Expected result: The PJR fetch finishes with status `LOADED`, loading ~700–1,050 PJR XML files, populating `ingest.s3_objects`, `pjr.pjr_file`, `pjr.event`, `pjr.event_line`, `pjr.tender_line`, `pjr.event_tax`, and passing all load checks.
The MCM fetch finishes `LOADED` with ~22 rows in `mcm.department_day`, and the day reads `match`:

```sql
SELECT business_date, status, mcm_sales, pjr_sales, departments_off
FROM report.rpt_mcm_day_check WHERE store_id = <storeId> ORDER BY business_date;
```

`mismatch` means a department differs (a PJR file is missing or a rule broke; `report.rpt_mcm_department_check`
shows which); `mcm_missing` / `pjr_missing` means one side of the day is not loaded yet.
