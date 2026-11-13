# stores-ingestion

Independent apps. Open **each folder in its own Claude session**; each has its own `CLAUDE.md` and `docs/`.

| Folder | What | Port | Status |
|---|---|---|---|
| [verifone-ingestion-service/](verifone-ingestion-service/) | Spring Boot: S3 CPJ zip → `verifone_db` | 8092 | scaffolded; build per `docs/VERIFONE_BUILD_GUIDE.md` |
| [ingestion-console/](ingestion-console/) | React admin UI for ingestion | 5182 | not started |
| [gilbarco-ingestion-service/](gilbarco-ingestion-service/) | Gilbarco ingestion | 8093 | later |
| [docs/](docs/) | history only | | superseded plan |
