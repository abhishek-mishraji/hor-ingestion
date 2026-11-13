# gilbarco-ingestion-service

Spring Boot 4.1.1 / Java 21 microservice on port **8093**. It downloads, cryptographically verifies, parses,
and loads Gilbarco Passport day packages (starting with PJR XML) from AWS S3 (`hor-stores-data`) into `gilbarco_db`.
Package `com.handsoffretail.gilbarco`.

## Work only in this folder

`stores-ingestion/` holds independent apps, each with its own Claude session. Do not edit `../ingestion-console`,
`../verifone-ingestion-service`, `hor-auth`, or `hor-store-automation`. Read them only.

## Start here

1. [docs/GILBARCO_SERVICE_PLAN.md](docs/GILBARCO_SERVICE_PLAN.md): Authoritative architectural decisions and design (SOLID file-type modularity).
2. [docs/GILBARCO_DATABASE_DESIGN.md](docs/GILBARCO_DATABASE_DESIGN.md): Database schema for `gilbarco_db` (schemas `master`, `ingest`, `pjr`, `mcm`, `report` + views). ER diagram: [docs/GILBARCO_DB_SCHEMA.mmd](docs/GILBARCO_DB_SCHEMA.mmd).
3. [docs/GILBARCO_BUILD_GUIDE.md](docs/GILBARCO_BUILD_GUIDE.md): The step-by-step implementation guide with full code specifications.
4. [docs/RUNNING_LOCALLY.md](docs/RUNNING_LOCALLY.md): Local setup, database creation, bootRun, and curl smoke tests.

## Key facts

- **SOLID File-Type Modularity**: Core fetch engine (`fetch`, `source`, `verify`, `stores`) is completely decoupled from individual file formats. `filetypes.pjr` implements `FileTypeProcessor`. Adding future formats (`FGM`, `MSM`, `MCM`, etc.) requires zero changes to PJR or the core fetcher.
- **MCM**: `filetypes.mcm` stores only the store-level file (period 2) in `mcm.department_day`. It names departments in `master.departments` (admin-set names stay) and proves each PJR day complete through `report.rpt_mcm_day_check` (`match` / `mismatch` / `mcm_missing` / `pjr_missing`). Rule 13 in the service plan.
- **PJR Engine**: Proven DOM parser and counting rules ported directly from `C:\Users\abhia\OneDrive\HOR-XML\pjr-platform` and `Wolf/POS_XML_FILE_TYPES.md`.
- **Security**: hor-auth `auth-client` 0.1.0 (from `mavenLocal`). PLATFORM_ADMIN only. Cookie `hor_access`; mutating writes require `X-HOR-Request: 1`.
- **Dev Database**: PostgreSQL 17 on `localhost:5432`. One login role, `auth_svc`, owns `auth_db`, `verifone_db`, `gilbarco_db`, and `gilbarco_db_test`. Run `infra/postgres/01_gilbarco_databases.sql` once as superuser.
- **Settings and Secrets**: `.env` in this folder (git-ignored; template in `.env.example`). Never print, log, or commit `.env`.
- **Stores**: `master.stores` is replicated exclusively via `POST /api/ingestion/gilbarco/stores/sync` from `auth_db.core.stores WHERE pos_vendor = 'GILBARCO'`.
- **Build and Test**: `./gradlew.bat test` and `./gradlew.bat build` in this folder.
