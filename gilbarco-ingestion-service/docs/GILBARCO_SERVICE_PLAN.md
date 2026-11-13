# gilbarco-ingestion-service: Implementation Plan (Authoritative)

Workspace: `stores-ingestion/gilbarco-ingestion-service`  
Service Port: **8093**  
Target Database: `gilbarco_db` (Dev: `localhost:5432`, owned by `auth_svc`)  
Written: 2026-10-23  

---

## 1. Executive Summary & Architecture Goals

`gilbarco-ingestion-service` is an independent, production-grade Spring Boot 4.1.1 / Java 21 microservice running on port **8093**. It downloads, cryptographically verifies, parses, and ingests Gilbarco Passport point-of-sale day packages from AWS S3 (`hor-stores-data`) into `gilbarco_db`.

### The Core Architectural Mandate: SOLID File-Specific Modularity
In `verifone-ingestion-service`, parsing, loading, and verification were tightly bound to a single file format (`CPJ`). When future file types (such as `FGM`, `MSM`, `MCM`, etc.) are introduced, that tightly-coupled structure makes isolation difficult.

For `gilbarco-ingestion-service`, we strictly enforce the **SOLID Principles** so that:
1. **File-Type Specific Separation (SRP)**: Each POS file format (starting with `PJR`, and ready for `FGM`, `MSM`, `MCM`, `TLM`, `ISM`, `TPM`, `FPM`) lives in its own dedicated, isolated sub-package (`com.handsoffretail.gilbarco.filetypes.<type>`).
2. **Pluggable & Extensible (OCP)**: The core ingestion pipeline (S3 download, manifest verification, fetch concurrency, store sync, audit tracking) is closed to modification but open to extension via the `FileTypeProcessor` SPI. Adding a future file type requires zero changes to existing PJR code or the core fetch engine.
3. **Single Spring Boot Runtime**: All file-type handlers run modularly within a single, unified Spring Boot application.
4. **Database Schema Isolation**: Each file type gets its own schema in `gilbarco_db` (e.g. `pjr.*`, and future `fgm.*`, `msm.*`), alongside shared schemas `master.*` and `ingest.*`.

---

## 2. Key Architecture Decisions

| # | Decision | Details & Rationale |
|---|---|---|
| **D1** | **SOLID File-Type Modularity** | Core engine (`fetch`, `source`, `stores`, `verify`) is completely decoupled from file types. `filetypes.pjr` implements `FileTypeProcessor`. Future types (`fgm`, `msm`) simply implement the interface without touching PJR. |
| **D2** | **Port Proven PJR Engine from `HOR-XML`** | Gilbarco Passport NAXML-POSJournal 3.4 parsing logic, XML event mapping, and StoreClose PDF-proven counting rules are ported directly from `C:\Users\abhia\OneDrive\HOR-XML\pjr-platform`. |
| **D3** | **Security Model (`auth-client` 0.1.0)** | Secured by `com.handsoffretail:auth-client:0.1.0` (from `mavenLocal`). All ingestion endpoints require authority `PLATFORM_ADMIN`. Cookie `hor_access`, header `X-HOR-Request: 1` on mutating requests. RFC 9457 Problem Details error responses. |
| **D4** | **Dedicated Database (`gilbarco_db`)** | Stored in PostgreSQL 17 on `localhost:5432`. In development, owned by `auth_svc` (same role owning `auth_db` and `verifone_db`). Test database is `gilbarco_db_test`. |
| **D5** | **Explicit Store Synchronization** | `master.stores` in `gilbarco_db` is refreshed strictly via `POST /api/ingestion/gilbarco/stores/sync` from `auth_db.core.stores WHERE pos_vendor = 'GILBARCO'`. |
| **D6** | **Day-Package S3 Contract (FastMirror)** | FastMirror 3.1.0 packs each day's files into: `s3://<bucket>/<s3_folder><yyyy>/<month>/<yyyy-MM-dd>/<TYPE>/<store>_<TYPE>_<yyyy-MM-dd>_v<n>.zip`. Inside is `manifest.json` + individual original POS XML files (~700–1,050 files for PJR). |
| **D7** | **Explicit `fileType` Ingestion API** | API endpoints accept an optional `fileType` (e.g. `fileType: "PJR"`, defaulting to `"PJR"`), allowing individual or parallel per-type ingestion tracking. |
| **D8** | **Database-Driven Reporting Views** | 10 StoreClose PDF-reconciled reporting views (`v_sold_line`, `v_void_line`, `rpt_day_summary`, etc.) are built into Flyway migrations under schema `report`. Ingestion backend handles the lifecycle; external services or dashboards read the views. |

---

## 3. SOLID Design Breakdown

### Single Responsibility Principle (SRP)
- `source.ZipSource`: Discovers and streams S3 objects for given store, date, and file type.
- `verify.ManifestVerifier`: Cryptographically verifies the zip package against `manifest.json` (SHA-256 hashes, file count, store folder).
- `fetch.FetchService`: Orchestrates the asynchronous fetch lifecycle (claim -> download -> verify -> load -> audit).
- `filetypes.pjr.parser.PjrParser`: Parses single NAXML-POSJournal DOM tree into clean in-memory records.
- `filetypes.pjr.load.PjrDayLoader`: Executes batch inserts into `pjr.*` tables and executes PJR-specific load checks.

### Open/Closed Principle (OCP)
Core fetch engine interacts with file types exclusively via the interface:
```java
public interface FileTypeProcessor {
    String fileType(); // e.g. "PJR", "FGM", "MSM"
    LoadOutcome processDay(FileLoadContext context);
    List<LoadCheck> supportedChecks();
}
```
Adding `FGM` or `MSM` later requires:
1. Creating `com.handsoffretail.gilbarco.filetypes.fgm.*`
2. Adding Flyway migration `V...__fgm_schema.sql`
3. Registering the `@Component` implementing `FileTypeProcessor`.
**Zero lines of code in `fetch/`, `source/`, `verify/`, or `filetypes/pjr/` need to be changed.**

### Liskov Substitution Principle (LSP)
Any implementation of `FileTypeProcessor` can be substituted into the `DayLoader` execution chain without unexpected side effects or breaking contracts.

### Interface Segregation Principle (ISP)
Interfaces are fine-grained:
- `ZipSource`: storage listing and streaming.
- `FileTypeProcessor`: day-level ingestion processing.
- `FileParser<T>`: stream-to-record conversion.
- `LoadCheck`: integrity validation execution.
- `StoreDirectory`: store metadata and permission checks.

### Dependency Inversion Principle (DIP)
High-level modules (`FetchService`, `DayService`) depend on abstractions (`FileTypeRegistry`, `ZipSource`, `StoreDirectory`), not concrete implementations (`PjrLoader`, `S3ZipSource`, `JdbcStoreDirectory`).

---

## 4. Gilbarco PJR Domain & Counting Rules (Proven against PDFs)

From `C:\Users\abhia\OneDrive\HOR-XML\Wolf\POS_XML_FILE_TYPES.md` and `pjr-platform`:

1. **One Transaction per File**: Each PJR XML file is a small (~2–25 KB) `NAXML-POSJournal 3.4` document holding exactly one `JournalReport` event.
2. **Exclude `SuspendFlag=yes`**: Parked prepay transaction snapshots must not count as sales (completed transactions are journalled separately).
3. **Exclude `VoidEvent`**: Voided transactions are cancelled; do not count in sales totals.
4. **Department Gross Calculation**:
   $$\text{Department Gross} = \text{SalesAmount} - \text{PromotionAmount} - \text{DiscountAmount}$$
   *(Note: Promotion and discount amounts in Passport are negative).*
5. **Fuel Gross Calculation**:
   $$\text{Fuel Gross} = \text{SalesAmount} - \text{Promotion}$$
6. **Customer Count**:
   $$\text{Customers} = \text{Completed SaleEvents} + \text{RefundEvents}$$
7. **Tender Identification**: Keyed by `tender_code` + `tender_sub` (e.g. `cash/generic`, `creditCards/generic`, `generic/generic` = EBT Food, `generic/store` = House Account, `outsideCredit/generic` = Pay at Pump).
8. **Lottery Handling**:
   - Dept 5 & 57 = `lottery_sale` (LOTTO SCRATCH OFF, LOTTO ONLINE).
   - Dept 6 = `lottery_payout` (LOTTO PAID OUT). Payouts appear as `RefundEvent` with negative line amount; never net against sales.
9. **TransactionID Wrapping**: Inside Register 2 wraps from `9999` to `1`. `TransactionID` is NOT globally unique over time; uniqueness requires `(store_code, register_id, transaction_id, business_date)`.
10. **ISO-8859-1 Encoding**: Gilbarco files use ISO-8859-1 encoding.
11. **Pay-ins (`pay_in`)** (owner decision, 2026-10-23): from October 2026 Passport also writes a `FinancialEvent` with
    `PayInDetail` (cash paid into the drawer; 2 of 10,514 files in 9-21 Oct). It loads as `detail_kind = 'pay_in'` with
    `DetailAmount` and `PayInReason` only; its `AccountInfo` (may name a person) is never read. It is never a sale
    (`counts_as_sale` false) and appears as kind `pay_in` in `report.rpt_cash_movement`. Not yet reconciled to a StoreClose PDF.
12. **Strict parsing** (owner decision, 2026-10-23): any other element the rules do not explain fails the whole day; the
    file is recorded in `pjr.load_failure` and the fetch ends `FAILED` naming the file.
13. **MCM, the second file type** (owner decision, 2026-10-27): load only the store-level MCM file (PrimaryReportPeriod 2)
    into one table, `mcm.department_day` (~22 rows per store per day). It does two jobs PJR cannot:
    - **names departments**: a scanned PJR item gives only the number; MCM names every department sold that day and
      upserts `master.departments.pos_name` (admin-set names stay, rule 9 / V5);
    - **proves the PJR day complete**: MCM `SalesAmount` = PJR `gross_sales + refund`, `-(Promotion + Discount)` =
      PJR `promotions`, `-RefundAmount` = PJR `refund`, per department. Held on every department of 14 Wolf days
      (2026-10-10..24). `report.rpt_mcm_day_check` says `match`, `mismatch`, `mcm_missing` or `pjr_missing`.
    The per-register file (period 98) and the raw XML are not stored. A difference is a soft load check (the MCM day
    stays loaded); a package without exactly one store-level file fails. Fetch it daily with `fileType: "MCM"`.

---

## 5. Target Package Structure

```
stores-ingestion/gilbarco-ingestion-service/
├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew*
├── .env.example
├── infra/postgres/01_gilbarco_databases.sql
├── docs/
│   ├── GILBARCO_SERVICE_PLAN.md      <-- This authoritative architecture plan
│   ├── GILBARCO_DATABASE_DESIGN.md  <-- Full SQL schema, ER diagram, view definitions
│   └── GILBARCO_BUILD_GUIDE.md      <-- Comprehensive step-by-step implementation guide
└── src/
    ├── main/
    │   ├── java/com/handsoffretail/gilbarco/
    │   │   ├── GilbarcoIngestionApplication.java
    │   │   ├── common/             # GilbarcoErrorCode, PageResult, Paging, PostgresErrors
    │   │   ├── config/             # DataSourceConfig, SecurityConfig, IngestionProperties, FetchConfig
    │   │   ├── stores/             # MasterStore, AuthStore, StoreDirectory, StoreSyncService, StoreController
    │   │   ├── source/             # ZipSource, LayoutZipSource, S3ZipSource, LocalFolderZipSource, ZipObject
    │   │   ├── verify/             # Manifest, ManifestVerifier, VerifiedZip, LoadCheck, LoadCheckRepository
    │   │   ├── fetch/              # FetchService, FetchWorker, FetchRepository, FetchStatusRecorder, StaleFetchSweeper, FetchController
    │   │   ├── days/               # DayService, DayController, LoadedDays
    │   │   ├── departments/        # DepartmentService, DepartmentController
    │   │   └── filetypes/          # SOLID File-Type Subsystem
    │   │       ├── FileTypeProcessor.java
    │   │       ├── FileTypeRegistry.java
    │   │       ├── FileLoadContext.java
    │   │       ├── LoadOutcome.java
    │   │       ├── pjr/            # Gilbarco PJR Implementation
    │   │       │   ├── PjrFileTypeProcessor.java
    │   │       │   ├── model/      # PjrRecords (Event, Line, Tender, Tax, ParsedFile)
    │   │       │   ├── parser/     # PjrParser (NAXML-POSJournal DOM parser)
    │   │       │   ├── load/       # PjrDayLoader, JdbcPjrRepository
    │   │       │   └── verify/     # PjrTenderBalanceCheck, PjrEventCountCheck, PjrGapCheck
    │   │       └── mcm/            # Gilbarco MCM Implementation (rule 13)
    │   │           ├── McmFileTypeProcessor.java
    │   │           ├── parser/     # McmParser (NAXML-MovementReport DOM parser), McmReport, McmFormatException
    │   │           ├── load/       # McmDayLoader, JdbcMcmRepository (mcm.department_day + department names)
    │   │           └── verify/     # McmDepartmentCountCheck (hard), McmPjrSalesCheck (soft)
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           ├── V1__gilbarco_master_and_ingest.sql
    │           ├── V2__pjr_tables.sql
    │           ├── V3__pjr_report_views.sql
    │           ├── V4__department_refund_void.sql
    │           ├── V5__department_admin_override.sql
    │           └── V6__mcm_department_day.sql
    └── test/
        ├── java/com/handsoffretail/gilbarco/
        │   ├── TestEnv.java, TestTokens.java, TestStores.java, TestBucket.java
        │   ├── stores/StoreSyncTest.java
        │   ├── source/S3ZipSourceContractTest.java, LocalFolderZipSourceContractTest.java
        │   ├── verify/ManifestVerifierTest.java
        │   ├── filetypes/pjr/
        │   │   ├── PjrParserTest.java
        │   │   └── PjrCountingRulesTest.java
        │   ├── filetypes/mcm/
        │   │   ├── McmXml.java
        │   │   └── McmParserTest.java
        │   └── integration/
        │       ├── GilbarcoIngestionIntegrationTest.java
        │       ├── McmIntegrationTest.java
        │       ├── RealS3FetchTest.java
        │       └── PjrGoldenDayTest.java
        └── resources/application-test.properties
```

---

## 6. HTTP API Specification (`/api/ingestion/gilbarco/**`)

All endpoints require `Cookie: hor_access=<JWT>` with authority `PLATFORM_ADMIN`.  
All mutating calls (`POST`, `PUT`, `DELETE`) require header `X-HOR-Request: 1`.

| Method | Path | Request Body | Description |
|---|---|---|---|
| `POST` | `/api/ingestion/gilbarco/stores/sync` | *None* | Pulls `GILBARCO` stores from `auth_db.core.stores` into `master.stores`. |
| `GET` | `/api/ingestion/gilbarco/stores` | *None* | Lists replicated stores (`storeId, storeCode, storeName, timezone, status, syncedAt`). |
| `GET` | `/api/ingestion/gilbarco/stores/{id}/available-days?year&month&fileType` | *Query params* | Days having a zip in S3 for `fileType` (defaults to `PJR`). |
| `GET` | `/api/ingestion/gilbarco/stores/{id}/loaded-days?from&to&fileType` | *Query params* | Days already loaded in `gilbarco_db` for `fileType` (defaults to `PJR`). |
| `GET` | `/api/ingestion/gilbarco/file-types` | *None* | `[{"fileType":"MCM","isDefault":false},{"fileType":"PJR","isDefault":true}]`: one per `FileTypeProcessor`. |
| `POST` | `/api/ingestion/gilbarco/fetches` | `{storeId, businessDate, mode: "REFUSE"\|"REPLACE", fileType: "PJR"\|"MCM"}` | Starts background fetch. Returns `202 {fetchRequestId: 123}`. |
| `GET` | `/api/ingestion/gilbarco/fetches?storeId&from&to&limit&offset` | *Query params* | Paginated history of fetch requests. |
| `GET` | `/api/ingestion/gilbarco/fetches/{id}` | *None* | Detail of fetch: status, timings, files loaded, row counts, load checks. |
| `GET` | `/api/ingestion/gilbarco/stores/{id}/departments` | *None* | Lists store departments with lottery classification. |
| `PUT` | `/api/ingestion/gilbarco/stores/{id}/departments/{deptNumber}` | `{name, salesClass, note?}` | Platform admin sets the name and class (`merchandise` / `lottery_sale` / `lottery_payout`); they then stay whatever the POS files say (V5). `422 BAD_DEPARTMENT` for a blank or too long name (max 60), another class or a note over 500. |
| `DELETE` | `/api/ingestion/gilbarco/stores/{id}/departments/{deptNumber}/override` | *None* | Returns the department to the POS name and the class it gives. |
| `GET` | `/api/ingestion/gilbarco/stores/{id}/departments/{deptNumber}/changes` | *None* | The admin changes of the department, newest first. |
| `GET` | `/api/ingestion/gilbarco/stores/{id}/departments/{deptNumber}/items?limit=25` | *None* | What was sold in the department (lines not cancelled, all loaded days): the `limit` (1..100, `422 BAD_LIMIT` otherwise) items with the most lines (`posCode`, `description`, `lineType`, `units`, `amount`, `lines`, `lastSold`) and totals `distinctItems`, `lines`, `amount`. Helps an admin name an unnamed department. |

---

## 7. Delivery Phases

- **Phase 0: Infrastructure & Environment**: Run superuser script `infra/postgres/01_gilbarco_databases.sql`, configure `.env`.
- **Phase 1: Gradle & Core Scaffolding**: Setup Gradle 9.8.1 wrapper, dependencies, `application.properties`, and base package structure.
- **Phase 2: Master & Ingestion Schemas**: Migrations `V1` (master & ingest), `V2` (pjr schema), and `V3` (report views).
- **Phase 3: Core Framework**: `common`, `config`, `stores` sync, `source` (S3/local), and `verify` (manifest checking).
- **Phase 4: SOLID FileType Engine & PJR Implementation**: `FileTypeProcessor` SPI, `PjrParser`, `PjrDayLoader`, and PJR reconciliation checks.
- **Phase 5: REST Controllers & Orchestration**: `StoreController`, `DayController`, `FetchController`, `DepartmentController`.
- **Phase 6: Verification & Test Suite**: Unit tests (`PjrParserTest`, `ManifestVerifierTest`) and integration tests (`GilbarcoIngestionIntegrationTest`, `PjrGoldenDayTest`).
- **Phase 7: MCM** (rule 13, done 2026-10-27): migration `V6` (`mcm.department_day`, `report.rpt_mcm_department_check`,
  `report.rpt_mcm_day_check`), `filetypes.mcm`, tests `McmParserTest`, `McmIntegrationTest` and the real-S3 MCM day
  (2026-10-24: 22 departments, `match`, $10,587.90). No change to `fetch`, `source`, `verify`, `stores` or `pjr`.
