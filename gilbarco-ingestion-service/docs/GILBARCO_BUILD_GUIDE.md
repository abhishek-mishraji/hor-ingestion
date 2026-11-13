# gilbarco-ingestion-service: Step-by-Step Implementation Build Guide

Authoritative technical instructions for implementing `gilbarco-ingestion-service`.  
Target Directory: `stores-ingestion/gilbarco-ingestion-service`  
Runtime: Java 21, Spring Boot 4.1.1, PostgreSQL 17, Gradle 9.8.1  
Service Port: **8093** · Database: `gilbarco_db`  

---

## 1. Rules for the Implementer

1. **Strictly adhere to SOLID principles**:
   - Keep all Gilbarco PJR-specific parsing, loading, models, and checks isolated in `com.handsoffretail.gilbarco.filetypes.pjr`.
   - Never couple `fetch`, `source`, `verify`, or `stores` directly to `PJR`.
   - Future file types (`FGM`, `MSM`, `MCM`, etc.) must be pluggable via `FileTypeProcessor` without modifying existing code.
2. **Do not reinvent proven PJR logic**: Port DOM parsing and calculation formulas directly from `C:\Users\abhia\OneDrive\HOR-XML\pjr-platform` and `Wolf/POS_XML_FILE_TYPES.md`.
3. **Database access & ownership**:
   - In dev, `auth_svc` owns `auth_db`, `verifone_db`, `gilbarco_db`, and `gilbarco_db_test`.
   - `auth_db` is accessed strictly in read-only mode during store synchronization (`POST /stores/sync`).
4. **Code style**: Java 21 `record`s for immutable data, constructor injection for Spring components, no Lombok, explicit transaction boundaries (`@Transactional` on services only), 4-space indent, RFC 9457 exceptions via `auth-client`.

---

## 2. Directory & Package Blueprint

```
src/main/java/com/handsoffretail/gilbarco/
├── GilbarcoIngestionApplication.java
├── common/
│   ├── GilbarcoErrorCode.java
│   ├── PageResult.java
│   ├── Paging.java
│   └── PostgresErrors.java
├── config/
│   ├── DataSourceConfig.java
│   ├── FetchConfig.java
│   ├── IngestionProperties.java
│   └── SecurityConfig.java
├── stores/
│   ├── AuthStore.java
│   ├── AuthStores.java
│   ├── MasterStore.java
│   ├── MasterStores.java
│   ├── StoreDirectory.java
│   ├── StoreRef.java
│   ├── StoreSyncService.java
│   └── StoreController.java
├── source/
│   ├── GilbarcoKeys.java
│   ├── LayoutZipSource.java
│   ├── LocalFolderZipSource.java
│   ├── S3ZipSource.java
│   ├── StoredObject.java
│   ├── ZipObject.java
│   ├── ZipSource.java
│   └── ZipSourceException.java
├── verify/
│   ├── CheckContext.java
│   ├── CheckResult.java
│   ├── JdbcLoadCheckRepository.java
│   ├── LoadCheck.java
│   ├── LoadCheckFailedException.java
│   ├── LoadCheckRepository.java
│   ├── Manifest.java
│   ├── ManifestVerifier.java
│   ├── VerifiedZip.java
│   └── ZipRejectedException.java
├── days/
│   ├── DayController.java
│   ├── DayService.java
│   └── LoadedDays.java
├── departments/
│   ├── DepartmentController.java
│   └── DepartmentService.java
├── fetch/
│   ├── FailureMessages.java
│   ├── FetchController.java
│   ├── FetchQueryService.java
│   ├── FetchRepository.java
│   ├── FetchRequest.java
│   ├── FetchService.java
│   ├── FetchStatusRecorder.java
│   ├── FetchWorker.java
│   ├── JdbcFetchRepository.java
│   └── StaleFetchSweeper.java
└── filetypes/
    ├── FileLoadContext.java
    ├── FileType.java
    ├── FileTypeProcessor.java
    ├── FileTypeRegistry.java
    ├── LoadOutcome.java
    └── pjr/
        ├── PjrFileTypeProcessor.java
        ├── load/
        │   ├── JdbcPjrRepository.java
        │   └── PjrDayLoader.java
        ├── model/
        │   └── PjrRows.java
        ├── parser/
        │   └── PjrParser.java
        └── verify/
            ├── PjrEventCountCheck.java
            ├── PjrPaymentGapCheck.java
            └── PjrTenderBalanceCheck.java
```

---

## 3. Step-by-Step Implementation Sequence

### Step 1: Database Provisioning & Flyway Migrations

1. Ensure the PostgreSQL databases exist. The owner runs `infra/postgres/01_gilbarco_databases.sql`:
   ```sql
   CREATE DATABASE gilbarco_db OWNER auth_svc ENCODING 'UTF8' TEMPLATE template0;
   CREATE DATABASE gilbarco_db_test OWNER auth_svc ENCODING 'UTF8' TEMPLATE template0;
   ```
2. Create migration `V1__gilbarco_master_and_ingest.sql` in `src/main/resources/db/migration/`:
   - Creates schemas `master` and `ingest`.
   - Creates `master.stores`, `master.departments`.
   - Creates `ingest.s3_objects`, `ingest.s3_object_files`, `ingest.fetch_requests`, `ingest.load_checks`.
3. Create migration `V2__pjr_tables.sql`:
   - Creates schema `pjr`.
   - Creates `pjr.pjr_file`, `pjr.event`, `pjr.event_line`, `pjr.tender_line`, `pjr.event_tax`, `pjr.load_failure`.
4. Create migration `V3__pjr_report_views.sql`:
   - Creates schema `report`.
   - Creates views `report.v_sold_line`, `report.v_void_line`, `report.rpt_day_summary`.

---

### Step 2: Configuration & Application Entry Point

1. **`application.properties`**:
   ```properties
   spring.application.name=gilbarco-ingestion-service
   spring.config.import=optional:file:.env[.properties]

   spring.datasource.url=jdbc:postgresql://${hor.gilbarco.db.host}:${hor.gilbarco.db.port}/gilbarco_db?reWriteBatchedInserts=true&logServerErrorDetail=false
   spring.datasource.username=${hor.gilbarco.db.username}
   spring.datasource.password=${hor.gilbarco.db.password}
   spring.datasource.hikari.maximum-pool-size=10
   spring.datasource.hikari.pool-name=gilbarco-db

   spring.flyway.enabled=true
   spring.flyway.clean-disabled=true
   spring.flyway.validate-on-migrate=true

   spring.jackson.default-property-inclusion=non_null

   server.port=8093
   server.address=127.0.0.1
   server.shutdown=graceful

   hor.gilbarco.db.host=${HOR_DB_HOST:localhost}
   hor.gilbarco.db.port=${HOR_DB_PORT:5432}
   hor.gilbarco.db.username=${HOR_DB_USERNAME:auth_svc}
   hor.gilbarco.db.password=${HOR_DB_PASSWORD}

   hor.aws.access-key-id=${HOR_AWS_ACCESS_KEY_ID:}
   hor.aws.secret-access-key=${HOR_AWS_SECRET_ACCESS_KEY:}

   hor.gilbarco.auth-db.url=jdbc:postgresql://${hor.gilbarco.db.host}:${hor.gilbarco.db.port}/auth_db?logServerErrorDetail=false
   hor.gilbarco.auth-db.username=${hor.gilbarco.db.username}
   hor.gilbarco.auth-db.password=${hor.gilbarco.db.password}

   hor.auth.client.jwks-uri=http://127.0.0.1:8091/api/auth/.well-known/jwks.json
   hor.auth.client.allowed-origins=http://localhost:5182,http://127.0.0.1:5182

   hor.s3.bucket=hor-stores-data
   hor.s3.region=us-east-1
   hor.storage.mode=${HOR_STORAGE_MODE:s3}
   hor.storage.local-root=${user.home}/.hor-stores/local-bucket

   hor.fetch.stale-after=PT30M
   hor.fetch.worker-threads=2
   hor.fetch.queue-capacity=20
   hor.fetch.max-uncompressed-bytes=524288000
   ```
2. **`DataSourceConfig.java`**:
   - Configures the primary `DataSource` and `JdbcTemplate` for `gilbarco_db`.
   - Configures a secondary read-only `DataSource` (named `authJdbcTemplate`) pointing to `auth_db` with max pool size 2.
3. **`SecurityConfig.java`**:
   - Secures `/api/ingestion/gilbarco/**` with `.hasAuthority("PLATFORM_ADMIN")`.
   - Enforces cookie-based JWT (`hor_access`) and CSRF protection requiring `X-HOR-Request: 1` on state-changing requests.

---

### Step 3: Common & Stores Sync Subsystem

1. **`common/GilbarcoErrorCode.java`**:
   - Extends standard domain errors: `NOT_FOUND` (404), `STORE_INACTIVE` (409), `FETCH_RUNNING` (409), `STORE_NOT_GILBARCO` (422), `DATE_IN_FUTURE` (422), `UNSUPPORTED_FILE_TYPE` (422).
2. **`stores/AuthStores.java`**:
   - Reads `auth_db.core.stores WHERE pos_vendor = 'GILBARCO'`.
3. **`stores/StoreSyncService.java`**:
   - Synchronizes Gilbarco stores from `auth_db` into `gilbarco_db.master.stores`.
   - Inserts new stores, updates modified stores (folder, timezone, status), deactivates absent stores.
   - Rejects store code mutations with conflict `STORE_CODE_MISMATCH`.
4. **`stores/StoreController.java`**:
   - `POST /api/ingestion/gilbarco/stores/sync` -> calls `StoreSyncService.sync(actorId)`.
   - `GET /api/ingestion/gilbarco/stores` -> lists replicated `master.stores`.

---

### Step 4: Storage & Key Abstraction (`source/`)

1. **`source/GilbarcoKeys.java`**:
   - Pure functions for matching S3 folder and file naming:
     ```
     s3://<bucket>/<s3_folder><yyyy>/<month>/<yyyy-MM-dd>/<TYPE>/<store>_<TYPE>_<yyyy-MM-dd>_v<n>.zip
     ```
   - Matches month folders ignoring case (`september/`).
   - Extracts version from `_v<n>.zip`. Highest version wins.
2. **`source/ZipSource.java` & `LayoutZipSource.java`**:
   - Extensible by file type:
     ```java
     Optional<ZipObject> findLatestZip(String storeFolder, LocalDate businessDate, String fileType);
     List<LocalDate> availableDays(String storeFolder, YearMonth month, String fileType);
     void download(ZipObject zip, Path target);
     ```
3. **`source/S3ZipSource.java`**:
   - AWS SDK v2 `S3Client`, paginates S3 prefixes, streams objects to disk.

---

### Step 5: Zip Verification (`verify/`)

1. **`verify/Manifest.java`**:
   - Maps FastMirror `manifest.json`:
     `{ "store": "...", "type": "PJR", "businessDate": "2026-09-24", "version": 1, "files": [...] }`
2. **`verify/ManifestVerifier.java`**:
   - Validates that every file in the zip is accounted for, matches its SHA-256 hash and size, matches store folder, and matches the requested `businessDate` and `fileType`.

---

### Step 6: SOLID FileType Engine (`filetypes/`)

1. **`filetypes/FileTypeProcessor.java`**:
   ```java
   public interface FileTypeProcessor {
       String fileType(); // "PJR", "FGM", etc.
       LoadOutcome processDay(FileLoadContext context);
   }
   ```
2. **`filetypes/FileLoadContext.java`**:
   - Encapsulates `(fetchRequestId, store, businessDate, replace, bucket, zip, verifiedZip)`.
3. **`filetypes/FileTypeRegistry.java`**:
   - Injects `List<FileTypeProcessor>` and maps them by `processor.fileType().toUpperCase()`.
   - Throws `UNSUPPORTED_FILE_TYPE` (422) if an unsupported type is requested.

---

### Step 7: Gilbarco PJR Engine (`filetypes/pjr/`)

1. **`parser/PjrParser.java`**:
   - Ported from `HOR-XML/pjr-platform/backend/.../PjrParser.java`.
   - XML DOM parse with secure entity resolving disabled.
   - Reads root `<NAXML-POSJournal>` -> `<TransmissionHeader>` -> `<JournalReport>` -> `<JournalHeader>` + Event.
   - Extracts Event, Lines (`ItemLine`, `MerchandiseCodeLine`, `FuelLine`, `FuelPrepayLine`), `TenderInfo`, and `TransactionTax`.
2. **`load/PjrDayLoader.java`**:
   - Runs inside the fetch load transaction:
     - If `replace = true`: deletes from `pjr.pjr_file` where `store_id = ?` and `business_date = ?` (cascades to lines, tenders, taxes).
     - Iterates through the ~700–1,050 files in the verified zip.
     - Parses each file using `PjrParser`.
     - Executes batch inserts into `pjr.pjr_file`, `pjr.event`, `pjr.event_line`, `pjr.tender_line`, and `pjr.event_tax`.
     - Upserts discovered department names into `master.departments`.
     - Executes PJR load checks and writes results to `ingest.load_checks`.
3. **`verify/PjrLoadChecks`**:
   - `PjrTenderBalanceCheck`: Validates that on all sale events, $\sum \text{Tenders} = \text{Total Grand}$.
   - `PjrEventCountCheck`: Confirms loaded event rows match the total files in the manifest.

---

### Step 8: Fetch Engine & API Controllers

1. **`fetch/FetchService.java`**:
   - `start(storeId, businessDate, mode, fileType, adminId)`:
     - Checks store exists, is Gilbarco, is ACTIVE, date not in future.
     - Claims row in `ingest.fetch_requests` (`status = CLAIMED`).
     - Submits to `FetchWorker`.
   - `process(fetchId)`:
     - Downloads latest zip for `fileType` via `ZipSource`.
     - Verifies zip with `ManifestVerifier`.
     - Resolves `FileTypeProcessor` from `FileTypeRegistry`.
     - Executes `processor.processDay(context)` inside a single transaction.
     - Transitions fetch status to `LOADED` (or `FAILED` on error).
2. **`fetch/FetchController.java`**:
   - `POST /api/ingestion/gilbarco/fetches`: `{storeId, businessDate, mode: "REFUSE"|"REPLACE", fileType: "PJR"}`.
   - `GET /api/ingestion/gilbarco/fetches`: Paginated fetch history.
   - `GET /api/ingestion/gilbarco/fetches/{id}`: Full fetch details, row counts, checks.
3. **`days/DayController.java`**:
   - `GET /api/ingestion/gilbarco/stores/{id}/available-days?year&month&fileType=PJR`
   - `GET /api/ingestion/gilbarco/stores/{id}/loaded-days?from&to&fileType=PJR`
4. **`departments/DepartmentController.java`**:
   - `GET /api/ingestion/gilbarco/stores/{id}/departments`

---

## 4. Testing & Verification

1. **Unit Tests**:
   - `PjrParserTest`: Validate parsing of Wolf XML samples (SaleEvent, RefundEvent, FinancialEvent, VoidEvent).
   - `ManifestVerifierTest`: Verify handling of valid manifest, corrupt hash, and missing file.
   - `StoreSyncTest`: Verify insertion and deactivation of Gilbarco stores.
2. **Integration Test**:
   - `GilbarcoIngestionIntegrationTest`:
     - Creates test store `800_NY-13208`.
     - Triggers `POST /api/ingestion/gilbarco/fetches`.
     - Verifies `status = LOADED`, `ingest.s3_objects` populated, `pjr.event` and `pjr.event_line` rows match manifest count, load checks pass.
     - Verifies `report.rpt_day_summary` ties to known Wolf totals.
3. **Build Command**:
   ```bash
   ./gradlew.bat test
   ./gradlew.bat build
   ```
