# verifone-ingestion-service

Spring Boot 4.1.1 / Java 21 service on port **8092**. It fetches one business day of a Verifone store's CPJ zip from S3
(`hor-stores-data`), verifies it, parses it and loads it into `verifone_db`. Package `com.handsoffretail.verifone`.

## Work only in this folder

`stores-ingestion/` holds independent apps, each with its own Claude session. Do not edit `../ingestion-console`,
`../gilbarco-ingestion-service`, `hor-auth` or `hor-store-automation`. Read them only.

## Start here

1. [docs/VERIFONE_SERVICE_PLAN.md](docs/VERIFONE_SERVICE_PLAN.md): decisions and why (authoritative).
2. [docs/VERIFONE_BUILD_GUIDE.md](docs/VERIFONE_BUILD_GUIDE.md): the step-by-step build with full code. Follow it exactly and run every Check.
3. [docs/Verifon_DATABASE_DESIGN.md](docs/Verifon_DATABASE_DESIGN.md), [docs/verifone_db.mmd](docs/verifone_db.mmd): schema reference.

## Key facts

- Port the old service `hor-store-automation/services/verifone-ingestion-service`; do not rewrite proven code.
- Security: hor-auth `auth-client` 0.1.0 (from `mavenLocal`). PLATFORM_ADMIN only. Cookie `hor_access`; writes need `X-HOR-Request: 1` and an allowed Origin.
- Dev DB: one login, `auth_svc` (password `HOR_DB_PASSWORD` in `.env`), owns `auth_db`, `verifone_db` and `verifone_db_test`.
- Settings and secrets: `.env` in this folder (git-ignored; keys documented in `.env.example`). Never print, log or commit `.env`.
- Stores: `master.stores` is refreshed only by `POST /api/ingestion/verifone/stores/sync` from `auth_db.core.stores`.
- Build and test: `./gradlew.bat build` (Git Bash) in this folder.
- Never run superuser SQL. The owner runs `infra/postgres/01_verifone_databases.sql`.
