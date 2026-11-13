# Running verifone-ingestion-service locally

The service fetches one business day of a Verifone store's CPJ zip from S3 (`hor-stores-data`), verifies it, and
loads it into `verifone_db`. It listens on `127.0.0.1:8092`. Every endpoint is for platform admins only.

## 1. Prerequisites

| What | Notes |
|---|---|
| JDK 21 | Gradle picks it up through the toolchain. |
| PostgreSQL 17 on `localhost:5432` | The same server that holds `auth_db`. `psql` is at `C:\Program Files\PostgreSQL\17\bin\psql.exe` (it is not on PATH). |
| `auth_svc` login | Created by `hor-auth/infra/postgres/01_auth_database.sql`. In development it owns `auth_db`, `verifone_db` and `verifone_db_test`. |
| `auth-client` 0.1.0 in `~/.m2` | Published by hor-auth (`publishToMavenLocal`). The build reads it from `mavenLocal()`. |
| hor-auth running on `127.0.0.1:8091` | Signs the `hor_access` cookie. This service checks it against hor-auth's JWKS. See `hor-auth/docs/RUNNING_LOCALLY.md`. |
| Git Bash | Run Gradle as `./gradlew.bat`. |

## 2. One-time setup (owner)

| # | Step |
|---|---|
| M1 | Create `.env` in this folder from `.env.example`, with the DB password and the read-only AWS key. `.env` is git-ignored. Never print it, log it or commit it. |
| M2 | Create the empty databases as `postgres` (run once): `psql -U postgres -f infra/postgres/01_verifone_databases.sql`. When the service starts, Flyway creates every schema (V1 verifone schema, V2 store replica). |
| M3 | Make sure Tiki Hut (`8730_IN-47872`, vendor VERIFONE, folder `Tiki_8730_IN-47872/Tiki_8730_IN-47872/`) exists in `auth_db.core.stores`. |
| M4 | **Required for `./gradlew.bat build`:** set `HOR_TESTDATA_ROOT` in `.env` to the folder with the real CPJ test days (forward slashes), for example `C:/Users/abhia/OneDrive/HOR-XML`. The integration tests build their stand-in bucket from these files. Without it, the parser test skips and the tests that start the application fail (see BUILD_REPORT.md). |

`verifone_db` must be empty, or hold only this service's own Flyway history, before the first start. If it has an
older schema, Flyway stops with "Migration checksum mismatch".

### `.env` keys

| Key | Meaning |
|---|---|
| `HOR_DB_HOST`, `HOR_DB_PORT` | PostgreSQL (default `localhost:5432`). |
| `HOR_DB_USERNAME`, `HOR_DB_PASSWORD` | `auth_svc` and its password. Used for `verifone_db` and for the read-only `auth_db` pool that the store sync uses. |
| `HOR_AWS_ACCESS_KEY_ID`, `HOR_AWS_SECRET_ACCESS_KEY` | The read-only key for `hor-stores-data`. Required when `HOR_STORAGE_MODE=s3`. |
| `HOR_STORAGE_MODE` | `s3` (the real bucket, default) or `local` (`~/.hor-stores/local-bucket`, laid out like the bucket). |
| `HOR_TESTDATA_ROOT` | The real CPJ test days (M4). Tests only. |

Real environment variables with the same names override `.env`, which is how production sets them.

## 3. Build and test

```bash
cd verifone-ingestion-service
./gradlew.bat build
```

The tests use `verifone_db_test`, which they clean and migrate at start. A minimal `core.stores` stands in for
`auth_db`, and zips come from a temporary bucket folder built from `HOR_TESTDATA_ROOT`. The S3 contract test reads
the real bucket with the `.env` key, read-only.

Gradle caches test results, and environment variables are not part of the cache key. After you change `.env`, run
`./gradlew.bat cleanTest build --no-build-cache` to make the tests run again.

## 4. Run

```bash
# hor-auth must already be running on 8091.
cd verifone-ingestion-service
./gradlew.bat bootRun
```

Run it from this folder: Spring reads `.env` from the working directory. Startup logs end with
`Tomcat started on port 8092` and `Started VerifoneIngestionApplication`. Stop it with Ctrl+C.

## 5. Smoke test (curl, Git Bash)

The admin login is `hor.auth.bootstrap.admin.email` / `.password` in `%USERPROFILE%\.hor-stores\secrets.properties`.
Type it in yourself; do not paste it into scripts that you commit or share.

```bash
J=/tmp/hor.cookies; O='Origin: http://localhost:5182'; H='X-HOR-Request: 1'
curl -s -c $J -H "$H" -H "$O" -H 'Content-Type: application/json' \
     -d '{"email":"<admin email>","password":"<admin password>"}' http://127.0.0.1:8091/api/auth/login
curl -s -b $J -H "$H" -H "$O" -X POST http://127.0.0.1:8092/api/ingestion/verifone/stores/sync
curl -s -b $J http://127.0.0.1:8092/api/ingestion/verifone/stores
curl -s -b $J "http://127.0.0.1:8092/api/ingestion/verifone/stores/<storeId>/available-days?year=2026&month=9"
curl -s -b $J -H "$H" -H "$O" -H 'Content-Type: application/json' \
     -d '{"storeId":<storeId>,"businessDate":"2026-09-08","mode":"REFUSE"}' http://127.0.0.1:8092/api/ingestion/verifone/fetches
curl -s -b $J http://127.0.0.1:8092/api/ingestion/verifone/fetches/<fetchRequestId>   # repeat until status != RUNNING
rm -f $J
```

If the login sets the cookie with `Secure`, curl will not send it over http. In that case, copy `hor_access` from
the login response's `Set-Cookie` header and send `-H "Cookie: hor_access=<value>"` instead.

Expected result: the sync reports the store as `inserted` (or `unchanged` on later syncs), and the fetch ends
`LOADED` with every hard check `passed: true`. A second REFUSE fetch of the same day ends `REFUSED`; use
`"mode":"REPLACE"` to load the day again.

## 6. API summary (`/api/ingestion/verifone`, PLATFORM_ADMIN; writes need `X-HOR-Request: 1` and an allowed Origin)

| Method | Path | |
|---|---|---|
| POST | `/stores/sync` | Copies the VERIFONE rows of `auth_db.core.stores` into `master.stores`. |
| GET | `/stores` | The synced stores. |
| GET | `/stores/{storeId}/available-days?year&month` | Days that have a CPJ zip in S3. |
| GET | `/stores/{storeId}/loaded-days?from&to` | Days already loaded (at most 400 days per request). |
| POST | `/fetches` | `{storeId, businessDate, mode: REFUSE\|REPLACE}` → `202 {fetchRequestId}`. |
| GET | `/fetches?storeId&from&to&limit&offset` | Fetch history. |
| GET | `/fetches/{id}` | Zip, files, rows per table, load checks. |
| GET | `/stores/{storeId}/departments` | Departments with name history and lottery class. |

Fetch statuses: `RUNNING, LOADED, REFUSED, NOT_FOUND, FAILED`. A fetch still `RUNNING` after 30 minutes is marked
`FAILED` at startup and every 5 minutes after that.
