# ingestion-console

Web UI for store ingestion: React 19 + **JavaScript (JSX, no TypeScript)** + Vite, dev port **5182**, served at
`http://localhost:5182/admin/`. The foundation plan is [docs/CONSOLE_PLAN.md](docs/CONSOLE_PLAN.md) and the multi-vendor Gilbarco plan is [src/GILBARCO_INTEGRATION_PLAN.md](src/GILBARCO_INTEGRATION_PLAN.md). Follow them.
For instructions on running locally, see [docs/RUNNING_LOCALLY.md](docs/RUNNING_LOCALLY.md).

## Work only in this folder

`stores-ingestion/` holds independent apps, each with its own session. Do not edit
`../verifone-ingestion-service`, `../gilbarco-ingestion-service` or `../../hor-auth`. Read their docs and code only, to learn
the API.

## Key facts

- **Backends**:
  - `verifone-ingestion-service` on `127.0.0.1:8092`, base path `/api/ingestion/verifone`.
  - `gilbarco-ingestion-service` on `127.0.0.1:8093`, base path `/api/ingestion/gilbarco`.
- **Login**: this app has **its own login page** at `/admin/login` and calls hor-auth on `127.0.0.1:8091`, `/api/auth` (`POST /login`, `GET /me`,
  `POST /refresh`, `POST /logout`). The session is the HttpOnly cookies `hor_access` and `hor_refresh`.
- **Browser code calls relative paths only** (`/api/auth/*`, `/api/ingestion/gilbarco/*`, `/api/ingestion/*`). The Vite proxy forwards them to
  8091, 8093, and 8092. The services enable no CORS, so never call `127.0.0.1:809x` from the browser. Use `credentials: 'same-origin'`.
- Every request sends the header `X-HOR-Request: 1` (writes are rejected without it). Open the app at `http://localhost:5182`;
  that Origin is the one the services allow.
- Only PLATFORM_ADMIN can use ingestion today.
- Vendor routes live under `/admin/:vendor/*` (`/admin/verifone/...`, `/admin/gilbarco/...`). Legacy un-prefixed Verifone URLs redirect to `/admin/verifone/...`.
