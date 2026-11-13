# verifone-ingestion-service: implementation plan (authoritative)

Workspace `C:\Users\abhia\OneDrive\HOR-Software\hor-stores\stores-ingestion` · service port **8092** · written 2026-10-22.

This file **supersedes** [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) (no-auth draft) and, for this repo,
`hor-stores/docs/verifon-HLD/VERIFONE_INGESTION_PLAN.md` (permission-based draft). Where they disagree, this file wins.

---

## 1. Owner decisions (2026-10-22)

| # | Decision |
|---|---|
| D1 | **Port, don't rewrite.** The old service `hor-store-automation/services/verifone-ingestion-service` is built and tested (parser, verifier, loader, 9 load checks, fetch lifecycle, golden tests). Copy it; change only what §5 lists. |
| D2 | **Security = `auth-client` 0.1.0, PLATFORM_ADMIN only.** Cookie `hor_access`, header `X-HOR-Request: 1` on writes, RFC 9457 errors. No hor-auth changes. Permission codes (`VERIFONE_VIEW/FETCH/REPLACE/DEPARTMENTS_EDIT`) are a later phase (§9). |
| D3 | **Backend only.** The React admin console (port 5182) is a later phase. |
| D4 | **Explicit store sync.** `verifone_db.master.stores` is a full replica of the VERIFONE rows of `auth_db.core.stores`, refreshed only by `POST /stores/sync`. Fetches and all reads use `master.stores`; `auth_db` is read only during a sync. |
| D5 | Java package `com.handsoffretail.verifone`, group `com.handsoffretail` (matches hor-auth). |
| D6 | Same PostgreSQL 17 on `localhost:5432` that holds `auth_db`. **Development: one login role, `auth_svc`, owns every HOR database** (`auth_db`, `verifone_db`, `verifone_db_test`); no extra role, no grant script. Production splits it later (config only). Old 5442 cluster is not used. |
| D7 | Settings and secrets in `verifone-ingestion-service/.env` (git-ignored; `.env.example` committed), imported by Spring as `optional:file:.env[.properties]`; real env vars override (production). The `postgres` superuser password is never stored in the project. |
| D8 | One folder per app under `stores-ingestion/` (`verifone-ingestion-service/`, `ingestion-console/`, `gilbarco-ingestion-service/`), each self-contained with its own `docs/` and opened in its own Claude session. Gilbarco: empty for now. |

## 2. Facts found in the code (correct the older docs)

1. The old service has **one** migration, `V1__verifone_schema.sql` (1,496 lines: schemas `master`, `ingest`, `cpj`, `report` + all views). The older plans' "V1–V9" does not exist. Copy V1 **byte for byte**.
2. Old `master.stores` has only `store_id, store_code, copied_at`. [Verifon_DATABASE_DESIGN.md](Verifon_DATABASE_DESIGN.md) §2.1 is the target. New `V2` adds the columns (§6).
3. 18 files import `com.handsoff.hor.shared.*` (`libs/shared-security`). That is the whole rewiring surface (§5.3).
4. `auth-client` 0.1.0 is already in `~/.m2` (`mavenLocal`). Its API: `HorPrincipal(accountType, id, role, name, allStores, storeIds)`, `StoreAccess.requireStore`, `DomainException(ErrorCode, detail)`, `CommonErrorCode`, `HorHttpSecurity.jwtAuthenticationConverter()`. Authorities are the bare role (`PLATFORM_ADMIN`, **no** `ROLE_` prefix), so use `hasAuthority`, not `hasRole`.
5. Toolchain: JDK 21 is present. `psql` is **not on PATH** (manual step M1). Gradle 9.8.1 wrapper and Spring Boot 4.1.1 come from hor-auth.
6. Old tests find real CPJ data through `hor.testdata.root` (system property, env `HOR_TESTDATA_ROOT`, or `~/.hor-store-automation/local.properties`).

## 3. Target layout

Each app in `stores-ingestion/` is a standalone folder, opened in its own Claude session, with its own docs.
There is no root build.

```
stores-ingestion/
├── docs/                              history only (superseded IMPLEMENTATION_PLAN.md)
├── ingestion-console/                 web UI (React, port 5182), separate session, later
├── gilbarco-ingestion-service/        later
└── verifone-ingestion-service/        THIS app (standalone Gradle build)
    ├── CLAUDE.md                      session instructions (auto-loaded)
    ├── docs/                          this plan, VERIFONE_BUILD_GUIDE.md, DB design + diagrams, RUNNING_LOCALLY.md, BUILD_REPORT.md
    ├── infra/postgres/01_verifone_databases.sql   verifone_db + verifone_db_test owned by auth_svc
    ├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew*, gradle/wrapper/, .gitignore, .gitattributes
    └── src/main/java/com/handsoffretail/verifone/
        ├── VerifoneIngestionApplication.java
        ├── common/   VerifoneErrorCode, PageResult, Paging, PostgresErrors   (new, local copies)
        ├── config/   DataSourceConfig, FetchConfig, IngestionProperties, SecurityConfig
        ├── stores/   StoreDirectory, StoreRef, MasterStore(s), AuthStore(s), StoreSyncService, StoreController
        ├── source/ verify/ parse/ load/   copied
        ├── fetch/ days/ departments/      copied, rewired (§5.3)
        └── resources/ application.properties, db/migration/V1__verifone_schema.sql, V2__store_replica.sql
```

## 4. Phases (Claude does all of these unless marked **[YOU]**)

| Phase | Work | Done when |
|---|---|---|
| **P0 [YOU]** | Manual prerequisites, §8 | `verifone_db`, `verifone_db_test` exist; grant script run; secrets added |
| P1 | **Done 2026-10-22:** wrapper, build files, `.gitignore`, `infra/postgres` script, per-app folders | `gradlew.bat help` runs (verified) |
| P2 | Copy `src/main` + `src/test` from the old service; rename package `com.handsoff.hor.ingestion.verifone` → `com.handsoffretail.verifone`; copy V1 unchanged | files in place (won't compile until P3) |
| P3 | Replace `shared-security` (§5.3); new `SecurityConfig`; `application.properties` (§7) | `gradlew.bat compileJava` green |
| P4 | Store replica: `V2__store_replica.sql`, `AuthStoreReader`, `StoreSyncService`, `MasterStoreDirectory`, `GET /stores`, `POST /stores/sync` (§6) | unit + integration tests for sync green |
| P5 | Port tests: `TestJwt`/`TestPlatform` → `TestTokens` (RS256 test key, cookie `hor_access`, header `X-HOR-Request`, test `JwtDecoder` bean); stores seeded via `master.stores` instead of `platform_db_test` | `gradlew.bat build` green (golden tests run if `hor.testdata.root` set, else skipped as before) |
| P6 | Run against real S3: log in, sync, fetch Tiki Hut `8730_IN-47872` one day (§8 M6) | fetch → `LOADED`, 9 load checks pass |
| P7 | `docs/RUNNING_LOCALLY.md`, `docs/BUILD_REPORT.md`; fix `Verifon_DATABASE_DESIGN.md` §2 (sync endpoint lives here, not in auth) | docs match code |

## 5. Code changes

### 5.1 Copied with package rename only

`source/*`, `parse/**`, `load/*`, `verify/*`, `fetch/{FetchWorker, StaleFetchSweeper, FetchRequest, FetchStatusRecorder*, repositories, queries}`,
`days/*`, `departments/*`, `config/{FetchConfig, IngestionProperties*}`. (*Edits from §5.3 only.)

### 5.2 Business rules kept exactly

Single-day fetch; highest `_vN` wins; month folder matched ignoring case; REFUSE checked before download **and** inside
the load transaction; download outside any transaction, one load transaction; FAILED written in its own transaction;
stale RUNNING (> 30 min) failed at start-up and every 5 min; unknown XML element fails the day naming its path; zip
deleted after load; old `MVP_REPORT.md` decisions 15–27.

### 5.3 Rewiring (the only real edits)

| Old | New |
|---|---|
| `shared.error.{ConflictException, NotFoundException, InvalidRequestException, DomainException, ErrorKind}` | `auth.client.DomainException` + `common.VerifoneErrorCode` (`FETCH_RUNNING` 409, `STORE_INACTIVE` 409, `STORE_CODE_MISMATCH` 409, `NOT_A_VERIFONE_STORE` 422, `FUTURE_BUSINESS_DATE` 422), `CommonErrorCode.{NOT_FOUND, VALIDATION_FAILED}` |
| `shared.web.{PageResult, Paging}`, `shared.db.PostgresErrors` | copied into `common/` (89 lines total) |
| `shared.security.{HorPrincipal, HorClaims, Role, TokenPolicy, AccessTokenVerifier, JwtAccessTokenVerifier, web.HorHttpSecurity}` | `auth.client.HorPrincipal` via `@AuthenticationPrincipal Jwt` → `HorPrincipal.from(jwt)`; JWT decoding by auth-client auto-config |
| `shared.security.store.{StoreAccess, KnownStores}` | `StoreDirectory.find(id)` (unknown → 404) then `auth.client.StoreAccess.requireStore(principal, id)`; `StoreDirectory` no longer extends `KnownStores` |
| `config.SecurityConfig`: `hasRole(PLATFORM_ADMIN)` | own `SecurityFilterChain` mirroring auth-client's default chain, with `/api/ingestion/verifone/**` → `hasAuthority("PLATFORM_ADMIN")`, `/error` permitAll, `anyRequest().denyAll()` |
| `config.DataSourceConfig` pool `platformJdbc` → `platform_db` | pool `authJdbc` → `auth_db`, read-only, 2 connections, user `verifone_ingest_svc` (`hor.verifone.auth-db.*`) |
| `stores.PlatformStoreDirectory` (reads platform_db per fetch) | `stores.MasterStoreDirectory` (reads `master.stores`) + `stores.AuthStoreReader` (reads `auth_db.core.stores`, used only by sync) |
| `stores.MasterStores.ensure()` called from `FetchStatusRecorder` | removed: the store row already exists after sync |
| `FetchService.start(..., long adminId)`, `DepartmentService.setLotteryClass(..., long adminId)` | unchanged signature; pass `principal.id()` (always an admin under D2) |

## 6. Store replica (D4)

### 6.1 `V2__store_replica.sql`

```sql
-- master.stores becomes a full replica of the VERIFONE rows of auth_db core.stores,
-- refreshed only by POST /api/ingestion/verifone/stores/sync. V1 comments that say
-- platform_db are historical: store_id = auth_db core.stores.store_id.
ALTER TABLE master.stores
    ADD COLUMN store_name text        NOT NULL,
    ADD COLUMN s3_folder  text        NOT NULL,
    ADD COLUMN timezone   text        NOT NULL,
    ADD COLUMN status     text        NOT NULL,
    ADD COLUMN synced_at  timestamptz NOT NULL,
    ADD CONSTRAINT uq_master_stores_s3     UNIQUE (s3_folder),
    ADD CONSTRAINT ck_master_stores_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
```

(Valid because `verifone_db` is new and `master.stores` is empty when V2 runs.)

### 6.2 Sync rules (`StoreSyncService.sync()`, one transaction)

1. Read `store_id, store_code, store_name, s3_folder, timezone, status FROM core.stores WHERE pos_vendor = 'VERIFONE'`.
2. New `store_id` → insert.
3. Existing `store_id`, same `store_code` → update name, folder, timezone, status, `synced_at` (count `updated` only if something changed).
4. Existing `store_id`, **different** `store_code` → do not touch; report conflict `STORE_CODE_MISMATCH` (codes are baked into S3 keys and loaded data).
5. `store_code` already used by another `store_id` → report conflict, skip.
6. Rows in `master.stores` that are no longer VERIFONE in `auth_db` (or are gone) → `status = 'INACTIVE'`. Never delete (FKs from `cpj`/`ingest`).
7. One log line with the counts and the actor id.

Response `200 {inserted, updated, deactivated, unchanged, conflicts: [{storeId, storeCode, reason}]}`.

### 6.3 Effect on fetches

- Store not in `master.stores` → `404 NOT_FOUND` ("Store not found. Run Sync stores.").
- `status = INACTIVE` → `409 STORE_INACTIVE`.
- S3 key built from `master.stores.s3_folder`. A folder change in auth_db takes effect at the next sync.

## 7. API `/api/ingestion/verifone` (all PLATFORM_ADMIN; writes need `X-HOR-Request: 1`)

| Method | Path | Notes |
|---|---|---|
| POST | `/stores/sync` | **new**, §6.2 |
| GET | `/stores` | **new**: rows of `master.stores` (`storeId, storeCode, storeName, timezone, status, syncedAt`) |
| GET | `/stores/{storeId}/available-days?year&month` | days with a CPJ zip in S3 |
| GET | `/stores/{storeId}/loaded-days?from&to` | as old |
| POST | `/fetches` | `{storeId, businessDate, mode: REFUSE\|REPLACE}` → `202 {fetchRequestId}` |
| GET | `/fetches?storeId&from&to&limit&offset` | history (limit default 50, max 500) |
| GET | `/fetches/{id}` | detail: zip, timings, row counts, load checks |
| GET | `/stores/{storeId}/departments` | as old (lottery class is fixed by department name in SQL; there is no PUT) |

Keep the old controllers' exact paths and JSON; only the store endpoints are new. Fetch statuses: `RUNNING, LOADED, REFUSED, NOT_FOUND, FAILED`.

Errors: malformed body → `400 VALIDATION_FAILED` (auth-client); rule breaks (`DATE_IN_FUTURE`, `BAD_RANGE`, ...) → `422`; conflicts → `409`.

### 7.1 `application.properties`

> **Superseded by [VERIFONE_BUILD_GUIDE.md](VERIFONE_BUILD_GUIDE.md) Step 2** (single `auth_svc` login, AWS keys
> `hor.aws.access-key-id` / `hor.aws.secret-access-key`). The draft below is kept for history.

```properties
spring.application.name=verifone-ingestion-service
spring.config.import=optional:file:${user.home}/.hor-stores/secrets.properties

spring.datasource.url=jdbc:postgresql://${hor.verifone.db.host}:${hor.verifone.db.port}/verifone_db?reWriteBatchedInserts=true&logServerErrorDetail=false
spring.datasource.username=verifone_ingest_svc
spring.datasource.password=${hor.verifone.db.password}
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.pool-name=verifone-db
spring.flyway.enabled=true
spring.flyway.clean-disabled=true
spring.flyway.validate-on-migrate=true
spring.jackson.default-property-inclusion=non_null

server.port=8092
server.address=127.0.0.1
server.shutdown=graceful

hor.verifone.db.host=localhost
hor.verifone.db.port=5432
hor.verifone.auth-db.url=jdbc:postgresql://${hor.verifone.db.host}:${hor.verifone.db.port}/auth_db?logServerErrorDetail=false
hor.verifone.auth-db.username=verifone_ingest_svc
hor.verifone.auth-db.password=${hor.verifone.db.password}

hor.auth.client.jwks-uri=http://127.0.0.1:8091/api/auth/.well-known/jwks.json
hor.auth.client.allowed-origins=http://localhost:5182

hor.s3.bucket=hor-stores-data
hor.s3.region=us-east-1
hor.storage.mode=s3
hor.storage.local-root=${user.home}/.hor-stores/local-bucket

hor.fetch.stale-after=PT30M
hor.fetch.worker-threads=2
hor.fetch.queue-capacity=20
hor.fetch.max-uncompressed-bytes=524288000
```

AWS keys keep the old names `hor.aws.access` / `hor.aws.secret` (read by `IngestionProperties.Aws`).

## 8. What YOU do manually (everything else is Claude's)

> **Superseded by the "Owner's manual steps" table at the end of [VERIFONE_BUILD_GUIDE.md](VERIFONE_BUILD_GUIDE.md)**
> (3 steps: AWS keys, one `psql` script, auth-service running). The table below is the older draft.

| # | Step | How |
|---|---|---|
| **M1** | Make `psql` usable | Add `C:\Program Files\PostgreSQL\17\bin` to PATH (or tell Claude the full path). |
| **M2** | Pick a password for `verifone_ingest_svc` and add secrets | Append to `%USERPROFILE%\.hor-stores\secrets.properties`:<br>`hor.verifone.db.password=<new password>`<br>`hor.aws.access=<copy from ~\.hor-store-automation\secrets.properties>`<br>`hor.aws.secret=<copy from same>` |
| **M3** | Create the databases (after Claude writes the scripts in P1) | as superuser `postgres`, from `stores-ingestion`:<br>`psql -U postgres -v verifone_pw='<pw>' -f infra/postgres/01_verifone_database.sql`<br>`psql -U postgres -v verifone_pw='<pw>' -f infra/postgres/02_verifone_test_database.sql` |
| **M4** | Let the service read `auth_db.core.stores` | `psql -U postgres -v reader_role=verifone_ingest_svc -f ..\hor-auth\infra\postgres\03_grant_store_reader.sql` |
| **M5** | (Optional, for golden tests) point at real CPJ data | Create `%USERPROFILE%\.hor-stores\local.properties` with `hor.testdata.root=<folder with the 20 golden days>`, or say "skip golden tests". |
| **M6** | End-to-end check (after P5) | 1) start auth-service (8091) and this service (8092)<br>2) make sure Tiki Hut `8730_IN-47872` exists in auth-console with vendor VERIFONE<br>3) tell Claude "run P6"; Claude does login → sync → fetch with curl using the bootstrap admin from secrets. |

Nothing in hor-auth is changed. Claude never runs superuser SQL or touches the old 5442 cluster.

## 9. Later phases (not now)

1. **Permissions** (HLD plan Phase A): hor-auth `V2__permissions.sql`, `perms` claim, auth-client 0.2.0; here swap `hasAuthority("PLATFORM_ADMIN")` for per-endpoint `PERM_VERIFONE_*` and add actor-type columns (`V3__actor_kind.sql`).
2. **Admin console** `web/admin-console` (React 19 + Vite, port 5182), as in the superseded plan §6, plus a "Sync stores" button.
3. **Gilbarco** service on 8093 with its own `gilbarco_db`.
4. MSM / FGM schemas in `verifone_db`.
