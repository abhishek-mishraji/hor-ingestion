# verifone-ingestion-service: build report

Built 2026-10-22 by following [VERIFONE_BUILD_GUIDE.md](VERIFONE_BUILD_GUIDE.md), Steps 1–7, in order.

## Result

| Finish-line item | Status |
|---|---|
| `grep -rn 'com\.handsoff\.hor' src` | Returns nothing. |
| `./gradlew.bat build` | Green. 47 tests run, 47 passed, 0 skipped (fresh run: `cleanTest build --no-build-cache`). |
| Step 6 (real S3 run) | Tiki Hut 2026-09-08 `LOADED`, 611 tickets, 20/20 hard checks passed. |
| Docs | [RUNNING_LOCALLY.md](RUNNING_LOCALLY.md) written. [Verifon_DATABASE_DESIGN.md](Verifon_DATABASE_DESIGN.md) §2.2 point 3 corrected. |

## What was built

- **Step 1.** The guide's copy script ran unchanged. The old `src/main`, `src/test` and `V1__verifone_schema.sql`
  (byte-identical, checked with `cmp`) were copied, and the package was renamed to `com.handsoffretail.verifone`.
  The list of files still importing the old shared library matched the guide's 13 files exactly.
- **Step 2.** `application.properties` and `application-test.properties` were written exactly as the guide shows.
- **Steps 3–4.** Every full-file block in the guide was copied into the source tree mechanically, by a script that
  reads the guide's code blocks, so there are no retyping errors: `common/*`, `V2__store_replica.sql`,
  `IngestionProperties`, `DataSourceConfig`, `SecurityConfig`, the stores package, and the rewired days, departments
  and fetch files. Before each copied file was replaced, it was diffed against the guide's version; the differences
  are the rewiring only, and the business logic is unchanged. The two `JdbcFetchRepository` edits were applied as
  shown. `SecurityConfig` matches auth-client's default chain line for line, except for the authorize rules.
- **Step 5.** `TestTokens`, `TestStores`, `TestEnv`, `IngestionIntegrationTest`, `IngestionApiTest` and
  `StoreSyncTest` came from the guide. The `FetchBehaviourTest`, `TestData` and `S3ZipSourceContractTest` edits
  were applied as shown (each one matched exactly once).
- **Step 6.** The real run is described below.

## Tests (`./gradlew.bat build`)

| Class | Run | Passed | Skipped |
|---|---|---|---|
| IngestionApiTest | 6 | 6 | 0 |
| fetch.FetchBehaviourTest | 9 | 9 | 0 |
| fetch.GoldenDayTest | 1 | 1 | 0 |
| fetch.SpotCheckTest | 1 | 1 | 0 |
| parse.CpjParserRealDataTest | 1 | 1 | 0 |
| parse.CpjParserRulesTest | 9 | 9 | 0 |
| source.CpjKeysTest | 4 | 4 | 0 |
| source.LocalFolderZipSourceContractTest | 6 | 6 | 0 |
| source.S3ZipSourceContractTest (real bucket, read-only) | 4 | 4 | 0 |
| stores.StoreSyncTest | 1 | 1 | 0 |
| verify.ManifestVerifierTest | 5 | 5 | 0 |
| **Total** | **47** | **47** | **0** |

No test was skipped, disabled, weakened or deleted. The real-data tests run because `HOR_TESTDATA_ROOT` is set
(deviation 3).

## Deviations from the guide

| # | Guide said | Reality | What I did | Why |
|---|---|---|---|---|
| 1 | §1.3 `build.gradle.kts`: `runtimeOnly("org.postgresql:postgresql")` only. §3.4 adds `common/PostgresErrors`. | `PostgresErrors` imports `org.postgresql.util.PSQLException` and `ServerErrorMessage`, so `compileJava` failed with 7 errors. In the old build, this class lived in `libs/shared-security`, which had `compileOnly("org.postgresql:postgresql")`. | Added `compileOnly("org.postgresql:postgresql")` next to the existing `runtimeOnly`, with a one-line comment. | This is the smallest fix and the same setup as the old library. It adds no new artifact, and the version comes from the Spring Boot BOM. |
| 2 | §5.8: `GoldenDays` "needs nothing beyond the Step 1 sed". | `GoldenDays.load()` reads `../../docs/test-data/expected_daily_tiki.csv`, relative to the old repo layout. From this folder that path does not exist, so `GoldenDayTest`, `SpotCheckTest` and `CpjParserRealDataTest` failed with `NoSuchFileException`. | Copied the CSV byte-for-byte (checked with `cmp`) from `hor-store-automation/docs/test-data/` to `docs/test-data/expected_daily_tiki.csv`. Changed the path to `Path.of("docs", "test-data", "expected_daily_tiki.csv")`. The class Javadoc already names that path. | This service is a standalone folder with no files shared with other apps. The CSV holds daily aggregate counts only, no personal data. The expected values are unchanged. |
| 3 | §5.8 / M4: "Tests that need real CPJ data skip themselves when `HOR_TESTDATA_ROOT` is empty", and M4 is optional. | Only `CpjParserRealDataTest` skips. Every test that extends `IngestionIntegrationTest` resolves `hor.storage.local-root` through `TestBucket.shared()` → `TestData.require()`. The failed assumption is thrown while the Spring context is being built, so the context fails to load and 18 tests **fail** instead of skipping. The old service always ran with `hor.testdata.root` set (in `~/.hor-store-automation/local.properties`). | After asking the owner, set `HOR_TESTDATA_ROOT=C:/Users/abhia/OneDrive/HOR-XML` in `.env`. This is the same folder the old service's tests used. No test code changed. | Keeps every test running with full coverage, as in the old service, without changing test behaviour. M4 is documented as **required** in RUNNING_LOCALLY.md. |
| 4 | §6 / V2 comment: `verifone_db` is new and `master.stores` is empty when V2 runs. | `verifone_db` already held a different schema: 9 migrations (`V1__stores_and_ingest.sql` … `V9__reporting_grants.sql`), applied by `auth_svc` on 2026-10-21. It also held data from an earlier run: 1 store, 2 fetches, 1,121 `cpj.tickets`. `bootRun` stopped with "Migration checksum mismatch" for versions 1 and 2. | With the owner's approval, dropped schemas `report, cpj, ingest, master` (CASCADE) and `public.flyway_schema_history`, as `auth_svc` (the owner, not a superuser), in one transaction. On restart, Flyway applied V1 and V2. | The guide assumes an empty database. Flyway cannot validate against a foreign history, and the business rules were not changed. |

## Observations (not changed)

- `SpotCheckTest.java:68` gives the compiler warning `[cast] redundant cast to OffsetDateTime`. The code is copied
  unchanged (rule: do not rewrite proven code). The build is not affected.
- [Verifon_DATABASE_DESIGN.md](Verifon_DATABASE_DESIGN.md) §2.1 does not match `V2__store_replica.sql`: it lists a
  `pos_vendor` column and `synced_at ... DEFAULT now()`. The V2 that was built has neither, plus the constraints
  `uq_master_stores_s3`, `ck_master_stores_name`, `ck_master_stores_s3` and `ck_master_stores_status`. Only §2.2 was
  in scope, so §2.1 is unchanged.
- `S3ZipSourceContractTest`'s Javadoc and skip message still say "secrets file". The guide's §5.9 edit changed only
  the code. The key now comes from `.env` (`TestEnv`).
- The fetch writes the zip to a temporary work file (`<java.io.tmpdir>/hor-verifone-ingestion/fetch-<id>.zip`),
  verifies it, loads it in one transaction, then deletes it. This is the old service's proven behaviour (plan §5.2,
  "zip deleted after load"), kept unchanged. A memory-only download would be a behaviour change for the owner to
  decide.
- Gradle's build cache does not see `.env` changes, so after editing `.env`, re-run the tests with
  `cleanTest build --no-build-cache`.

## Step 6: real S3 run

Run 2026-10-22, with `./gradlew.bat bootRun` (storage mode `s3`) and hor-auth on 8091. The login used the bootstrap
admin, read from the secrets file by the smoke script and never printed.

| Call | Result |
|---|---|
| `POST /api/auth/login` | 200, `hor_access` cookie set |
| `POST /stores/sync` | 200 `{"inserted":1,"updated":0,"deactivated":0,"unchanged":0,"conflicts":[]}` |
| `GET /stores` | Tiki Hut: storeId **1**, `8730_IN-47872`, folder `Tiki_8730_IN-47872/Tiki_8730_IN-47872/`, `America/Indiana/Indianapolis`, ACTIVE |
| `GET /stores/1/available-days?year=2026&month=9` | 29 days (2026-09-01 … 2026-09-30, no zip for 09-26) |
| `POST /fetches` `{storeId:1, businessDate:"2026-09-08", mode:"REFUSE"}` | 202 `{"fetchRequestId":1}` |
| `GET /fetches/1` | **LOADED** after 5.7 s |

- **Zip:** `s3://hor-stores-data/Tiki_8730_IN-47872/Tiki_8730_IN-47872/2026/september/2026-09-08/CPJ/Tiki_8730_IN-47872_CPJ_2026-09-08_v1.zip`,
  version 1, 360,985 bytes, mirror 3.0.0.
- **File:** `CPJ2026-09-08-585.xml` (day sequence 585). Message: "loaded 1 file(s): CPJ2026-09-08-585.xml
  (611 tickets, all checks passed)".
- **Rows:** tickets 611, ticket_lines 1,167, payment_lines 745, card_payments 347, ticket_taxes 429, fuel_lines 175,
  age_checks 130, cashier_events 44, loyalty 10, event_lines 6, cashier_sessions 2, period_closes 2, coupons 0.
- **Load checks:** 22 check rows covering the 9 check types; the count reconciliation expands into 13 rows.
  **20/20 hard checks passed** and 2/2 soft checks passed:
  - `codes_of_same_store`, `manifest_and_sha256`, `no_unmapped_input`, `register_physical_id_consistent`,
    `root_cashier_copies_equal` and `store_number_equals_site` passed.
  - All 13 `count: …` checks (XML second pass vs rows stored) passed.
  - Soft: `coupon_total` passed, and `pos_sales_counter` passed (11,888.82 = 11,888.82).
- **Database:** `SELECT count(*) FROM cpj.tickets` in `verifone_db` returns **611**; this is the only day loaded.
- The work-folder zip was deleted after the load, and the service was stopped afterwards.
