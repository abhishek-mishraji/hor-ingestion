# ingestion-console: Gilbarco integration plan

Adds **gilbarco-ingestion-service** (port **8093**) to the console, which today talks only to verifone-ingestion-service
(port 8092). One app, one login, two vendors. Verifone behaviour must not change.

Written 2026-10-23 from the code of both services (controllers, response records, tests), not from guesses.
Authoritative backend sources, read-only from this app:

| What | Where |
|---|---|
| Gilbarco API (controllers) | `../gilbarco-ingestion-service/src/main/java/com/handsoffretail/gilbarco/{stores,days,fetch,departments,filetypes}/*Controller.java` |
| Gilbarco error codes | `../gilbarco-ingestion-service/src/main/java/com/handsoffretail/gilbarco/common/GilbarcoErrorCode.java` |
| Gilbarco API exercised end to end | `../gilbarco-ingestion-service/src/test/java/com/handsoffretail/gilbarco/integration/GilbarcoIngestionIntegrationTest.java` |
| Gilbarco design | `../gilbarco-ingestion-service/docs/GILBARCO_SERVICE_PLAN.md` |
| Console rules | `../CLAUDE.md`, `../docs/CONSOLE_PLAN.md` (still authoritative for everything this file does not change) |

---

## 0. Hard rules (unchanged from CONSOLE_PLAN.md, plus four)

1. Work only in `ingestion-console/`. Never edit the services or hor-auth.
2. JavaScript + JSX only, no TypeScript, no new runtime dependencies (React 19, react-router 7, Vite).
3. Browser code calls **relative paths only** through the Vite proxy; `credentials: 'same-origin'`; every request sends
   `X-HOR-Request: 1` (already done by `src/api/client.js`; reuse it, do not write a second client).
4. **No vendor `if`s spread across pages.** Vendor differences live in the API adapters (§5) and one vendor registry (§4).
   Pages receive the same normalized shapes for both vendors.
5. **Verifone URLs keep working.** Old links (`/admin/stores`, `/admin/fetches/12`, `/admin/history?storeId=1`) redirect to
   their `/verifone/...` equivalent.
6. Gilbarco has a **file type** (PJR today; FGM, MSM, MCM later). The console never hard-codes the list: it reads
   `GET /api/ingestion/gilbarco/file-types`.
7. The gate for every step: `npm run lint && npm run build` with zero warnings.

---

## 1. What changes for the user

- A **vendor switcher** in the header: **Verifone | Gilbarco**. The brand badge shows the current vendor.
- Every screen (Stores, Fetch, Fetch detail, History, Departments) works for both vendors, under `/admin/<vendor>/...`.
- Gilbarco screens show a **File type** selector (only PJR today; hidden when a single type exists, but the value is
  still sent).
- Gilbarco fetch detail shows **one row-count table for the day** and **one load-check table** (Gilbarco loads ~700-1,050
  files per day as one unit; Verifone shows per-file sections).

---

## 2. Backend contract: Gilbarco API (base `/api/ingestion/gilbarco`)

All endpoints: cookie `hor_access`, role `PLATFORM_ADMIN` only (others get 403). Writes (`POST`) need `X-HOR-Request: 1`
and Origin `http://localhost:5182`. Errors are RFC 9457 problem details with a `code` property. JSON omits null fields
(`spring.jackson.default-property-inclusion=non_null`): treat a missing field as null.

### 2.1 Endpoint table (side by side)

| # | Purpose | Verifone (8092) | Gilbarco (8093) |
|---|---|---|---|
| 1 | List file types | (none: CPJ only) | `GET /file-types` |
| 2 | List stores | `GET /stores` | `GET /stores` |
| 3 | Sync stores from auth_db | `POST /stores/sync` | `POST /stores/sync` |
| 4 | Days with a zip in S3 | `GET /stores/{id}/available-days?year&month` | `GET /stores/{id}/available-days?year&month&fileType=PJR` |
| 5 | Days already loaded | `GET /stores/{id}/loaded-days?from&to` | `GET /stores/{id}/loaded-days?from&to&fileType=PJR` |
| 6 | Start a fetch | `POST /fetches` `{storeId,businessDate,mode}` | `POST /fetches` `{storeId,businessDate,mode,fileType}` |
| 7 | Fetch detail (poll) | `GET /fetches/{id}` | `GET /fetches/{id}` |
| 8 | Fetch history | `GET /fetches?storeId&from&to&limit&offset` | `GET /fetches?storeId&from&to&fileType&limit&offset` |
| 9 | Departments | `GET /stores/{id}/departments` | `GET /stores/{id}/departments` |

`fileType` is optional everywhere on Gilbarco (missing or blank = `PJR`, case-insensitive). The console **always sends it**.
On history, omitting `fileType` lists every type.

### 2.2 Gilbarco request / response shapes (exact)

**1. `GET /file-types`** → `200`
```json
[ { "fileType": "PJR", "isDefault": true } ]
```

**2. `GET /stores`** → `200` (same shape as Verifone)
```json
[ { "storeId": 2, "storeCode": "800_NY-13208", "storeName": "Wolf",
    "s3Folder": "wolf_800_NY-13208/wolf_800_NY-13208/", "timezone": "America/New_York",
    "status": "ACTIVE", "syncedAt": "2026-10-23T04:35:28.181Z" } ]
```
Only stores with `pos_vendor = 'GILBARCO'` in auth_db, after a sync.

**3. `POST /stores/sync`** (no body) → `200` (same shape as Verifone)
```json
{ "inserted": 1, "updated": 0, "deactivated": 0, "unchanged": 0,
  "conflicts": [ { "storeId": 10, "storeCode": "SYNC_10X", "reason": "STORE_CODE_MISMATCH: ..." } ] }
```

**4. `GET /stores/2/available-days?year=2026&month=10&fileType=PJR`** → `200`
```json
[ "2026-10-09", "2026-10-10", "2026-10-12", "2026-10-13" ]
```
Reads S3 live (slow: seconds). Keep the existing "Check S3" button behaviour (on demand, never automatic).

**5. `GET /stores/2/loaded-days?from=2026-10-01&to=2026-10-31&fileType=PJR`** → `200`
```json
[ { "businessDate": "2026-10-15", "versionNo": 1, "files": 974,
    "loadedAt": "2026-10-23T04:40:02.512Z", "s3ObjectId": 7 } ]
```
Differences from Verifone's LoadedDay: **no `daySeqs`, no `fetchRequestId`; has `s3ObjectId`.** Range at most 400 days
(else 422 `BAD_RANGE`).

**6. `POST /fetches`** body `{"storeId":2,"businessDate":"2026-10-15","mode":"REFUSE","fileType":"PJR"}` → `202`
```json
{ "fetchRequestId": 42 }
```

**7. `GET /fetches/42`** → `200`
```json
{
  "fetch": { "id": 42, "storeId": 2, "businessDate": "2026-10-15", "fileType": "PJR", "mode": "REPLACE",
             "status": "LOADED", "message": "loaded 974 PJR file(s): {sale=..., ...}; ... ; all checks passed",
             "requestedBy": 1, "requestedAt": "2026-10-23T04:39:58.100Z",
             "finishedAt": "2026-10-23T04:40:02.900Z", "durationMs": 4800 },
  "zip":   { "bucket": "hor-stores-data",
             "key": "wolf_800_NY-13208/wolf_800_NY-13208/2026/october/2026-10-15/PJR/wolf_800_NY-13208_PJR_2026-10-15_v1.zip",
             "fileType": "PJR", "version": 1, "etag": "…", "sizeBytes": 1234567,
             "lastModified": "…", "manifestCreatedAt": "…", "mirrorVersion": "3.1.0", "files": 974 },
  "rows":  { "pjr.event": 974, "pjr.event_line": 5321, "pjr.event_tax": 812,
             "pjr.pjr_file": 974, "pjr.tender_line": 1530 },
  "checks": [
    { "name": "manifest_and_sha256", "expected": 974, "actual": 974, "passed": true, "hard": true, "detail": "…" },
    { "name": "pjr_files_equal_manifest", "expected": 974, "actual": 974, "passed": true, "hard": true, "detail": "…" },
    { "name": "pjr_events_equal_manifest", "expected": 974, "actual": 974, "passed": true, "hard": true, "detail": "…" },
    { "name": "pjr_events_of_business_date", "expected": 0, "actual": 0, "passed": true, "hard": true, "detail": "…" },
    { "name": "pjr_payment_gaps", "expected": 0, "actual": 0, "passed": true, "hard": false, "detail": "…" },
    { "name": "pjr_tenders_equal_grand_total", "expected": 12108.02, "actual": 12108.02, "passed": true, "hard": false, "detail": "…" }
  ]
}
```
- `zip`, `rows`, `checks` are present only once the fetch reached a zip; before that (`CLAIMED`/`RUNNING`, `NOT_FOUND`,
  `REFUSED`, early `FAILED`) `zip` is missing and `rows` / `checks` are `{}` / `[]`.
- `rows` are the rows **currently** stored from that zip (a later REPLACE of the same zip shows the new counts).
- `expected` / `actual` are numbers (counts or money), possibly missing. **Check names differ per file type**: render them
  generically, never switch on a name.
- Differences from Verifone's FetchDetail: **no `files[]` array; `rows` and `checks` are top level; `fetch.fileType`
  and `zip.fileType`, `zip.files` exist.**

**Status values** (Gilbarco): `CLAIMED` → `RUNNING` → one of `LOADED`, `REFUSED`, `NOT_FOUND`, `FAILED`.
**`CLAIMED` is new** (queued, worker not started). Open = `CLAIMED` or `RUNNING`: keep polling while open.

**8. `GET /fetches?storeId=2&fileType=PJR&limit=50&offset=0`** → `200`
```json
{ "items": [ { "id": 42, "storeId": 2, "businessDate": "2026-10-15", "fileType": "PJR", "mode": "REPLACE",
               "status": "LOADED", "message": "…", "requestedBy": 1, "requestedAt": "…",
               "finishedAt": "…", "durationMs": 4800 } ],
  "total": 1, "limit": 50, "offset": 0 }
```
`limit` 1..500 (default 50) else 422 `BAD_LIMIT`; `offset` ≥ 0 else 422 `BAD_OFFSET`.

**9. `GET /stores/2/departments`** → `200`
```json
[ { "deptNumber": 1, "deptName": "Grocery", "salesClass": "merchandise", "updatedAt": "…" },
  { "deptNumber": 4, "salesClass": "merchandise", "updatedAt": "…" },
  { "deptNumber": 5, "deptName": "LOTTO SCRATCH OFF", "salesClass": "lottery_sale", "updatedAt": "…" },
  { "deptNumber": 6, "deptName": "LOTTO PAID OUT", "salesClass": "lottery_payout", "updatedAt": "…" } ]
```
Differences from Verifone: **`deptName` (may be missing: a department never named by a ring) instead of `currentName`;
no `names[]` history; `salesClass` ∈ `merchandise | lottery_sale | lottery_payout | fuel` instead of
`lotteryClass` ∈ `LOTTERY_SALE | LOTTERY_PAYOUT | null`.**

### 2.3 Gilbarco error codes (problem `code`)

| Code | HTTP | When | Console message |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | missing/invalid body field (`errors[]` per field) | existing |
| `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 401 | no / expired cookie | existing refresh flow |
| `FORBIDDEN`, `CSRF_REJECTED` | 403 | not PLATFORM_ADMIN / no header | existing |
| `NOT_FOUND` | 404 | unknown store / fetch, or a non-Gilbarco store id | show `detail` |
| `FETCH_RUNNING` | 409 | a fetch of that store + day + file type is open | "A fetch of this store, day and file type is already running." |
| `FETCH_QUEUE_FULL` | 409 | worker queue full | existing |
| `STORE_INACTIVE` | 409 | store INACTIVE | show `detail` |
| `STORE_NOT_GILBARCO` | 422 | (defensive) | "This store is not a Gilbarco store." |
| `DATE_IN_FUTURE` | 422 | business date after today in the store's timezone | show `detail` |
| `UNSUPPORTED_FILE_TYPE` | 422 | file type not served | show `detail` (lists supported types) |
| `BAD_RANGE`, `BAD_LIMIT`, `BAD_OFFSET` | 422 | query out of range | show `detail` (already handled by `BAD_` rule) |

`BACKEND_DOWN` text must become vendor-aware: "gilbarco-ingestion-service on 8093" vs "verifone-ingestion-service on 8092".

---

## 3. Networking

### 3.1 `vite.config.js`: Gilbarco proxy **before** the generic one

Vite tries proxy keys in insertion order and uses the first prefix match, so the specific key must come first:
```js
proxy: {
  '/api/auth': { target: 'http://127.0.0.1:8091', changeOrigin: false },
  '/api/ingestion/gilbarco': { target: 'http://127.0.0.1:8093', changeOrigin: false },
  '/api/ingestion': { target: 'http://127.0.0.1:8092', changeOrigin: false },   // verifone
},
```
Same cookie, same Origin: the hor_access cookie is host-only on `localhost:5182`, so it reaches both services.

### 3.2 Production note (not built now)
nginx on `apps.handsoffretail.com` must route `/api/ingestion/gilbarco/` to the Gilbarco service before `/api/ingestion/`.

---

## 4. Vendor registry (the only place that knows vendors)

New `src/vendors/vendors.js`:
```js
export const VENDORS = {
  verifone: { id: 'verifone', label: 'Verifone', port: 8092, service: 'verifone-ingestion-service',
              hasFileTypes: false, hasDeptHistory: true },
  gilbarco: { id: 'gilbarco', label: 'Gilbarco', port: 8093, service: 'gilbarco-ingestion-service',
              hasFileTypes: true,  hasDeptHistory: false },
};
export const DEFAULT_VENDOR = 'verifone';
export const isVendor = (v) => Object.hasOwn(VENDORS, v);
```
New `src/vendors/VendorProvider.jsx`: reads `:vendor` from the route, exposes `{ vendor, config, api }` via `useVendor()`,
where `api` comes from §5. An unknown vendor segment renders NotFoundPage.

---

## 5. API layer: one normalized interface, two adapters

Keep `client.js` and `verifoneApi.js` as they are (verifoneApi stays the raw client). Add:

- `src/api/gilbarcoApi.js`: raw client for §2 (base `/api/ingestion/gilbarco`), mirror `verifoneApi.js` style and JSDoc
  typedefs: `listFileTypes()`, `listStores()`, `syncStores()`, `getAvailableDays(storeId, year, month, fileType)`,
  `getLoadedDays(storeId, from, to, fileType)`, `startFetch({storeId, businessDate, mode, fileType})`, `getFetch(id)`,
  `listFetches({storeId, from, to, fileType, limit, offset})`, `listDepartments(storeId)`.
- `src/api/ingestionApi.js`: `apiFor(vendor)` returns the **normalized** interface below; it wraps the raw clients and
  maps their payloads. Pages call only this.

Normalized interface (identical for both vendors):

| Method | Returns (normalized) |
|---|---|
| `listFileTypes()` | `string[]`: Verifone `['CPJ']` (constant, no call); Gilbarco from `GET /file-types`, default first |
| `listStores()`, `syncStores()` | unchanged shapes |
| `availableDays(storeId, y, m, fileType)` | `string[]` |
| `loadedDays(storeId, from, to, fileType)` | `[{ businessDate, versionNo, files, loadedAt, fetchRequestId?: number }]` (Verifone keeps `daySeqs` as an extra) |
| `startFetch({storeId, businessDate, mode, fileType})` | `{ fetchRequestId }` (Verifone ignores `fileType`) |
| `getFetch(id)` | `{ fetch, zip?, sections: [{ title, rows: {table: count}, checks: Check[] }] }` |
| `listFetches(params)` | `{ items, total, limit, offset }`, each item has `fileType` (Verifone: `'CPJ'`) |
| `listDepartments(storeId)` | `[{ deptNumber, name: string|null, salesClass: 'merchandise'|'lottery_sale'|'lottery_payout'|'fuel', history?: DeptName[] }]` |

Mappings:
- **getFetch, Verifone:** one section per `files[i]`, title = `filePath` (day seq), rows/checks from the file.
- **getFetch, Gilbarco:** one section, title = `"<fileType> day package (<zip.files> files)"`, rows = `rows`, checks = `checks`;
  `sections: []` when `rows` is empty and `checks` is empty.
- **Departments, Verifone:** `name = currentName`, `salesClass` = `LOTTERY_SALE→lottery_sale`, `LOTTERY_PAYOUT→lottery_payout`,
  else `merchandise`; `history = names`.
- **Departments, Gilbarco:** `name = deptName ?? null`, `salesClass` as is, `history` undefined. Also `posName`, `adminManaged`, `adminSetBy`, `adminSetAt`, `lastSold`; the Gilbarco adapter adds `setDepartment`, `resetDepartment` and `departmentChanges` (admin name / sales class, see docs/CONSOLE_PLAN.md 8.6).
- **Status:** shared helper `isOpen(status) = status === 'CLAIMED' || status === 'RUNNING'`.

---

## 6. Routes (`App.jsx`)

```jsx
<Route path="/login" element={<LoginPage />} />
<Route element={<RequireAdmin><Outlet /></RequireAdmin>}>
  <Route index element={<Navigate to="/verifone/stores" replace />} />
  <Route path="/:vendor" element={<VendorProvider><StoresProvider><Layout /></StoresProvider></VendorProvider>}>
    <Route index element={<Navigate to="stores" replace />} />
    <Route path="stores" element={<StoresPage />} />
    <Route path="fetch" element={<FetchPage />} />              {/* ?storeId=&month=YYYY-MM&fileType= */}
    <Route path="fetches/:id" element={<FetchDetailPage />} />
    <Route path="history" element={<HistoryPage />} />          {/* ?storeId=&from=&to=&fileType=&offset=&limit= */}
    <Route path="departments" element={<DepartmentsPage />} />  {/* ?storeId= */}
    <Route path="*" element={<NotFoundPage />} />
  </Route>
  {/* legacy Verifone links */}
  <Route path="/stores" element={<LegacyRedirect to="stores" />} />
  <Route path="/fetch" element={<LegacyRedirect to="fetch" />} />
  <Route path="/fetches/:id" element={<LegacyRedirect to="fetches/:id" />} />
  <Route path="/history" element={<LegacyRedirect to="history" />} />
  <Route path="/departments" element={<LegacyRedirect to="departments" />} />
</Route>
```
`LegacyRedirect` keeps the query string and maps to `/verifone/...`. All in-app links become vendor-relative
(`useVendor().vendor` + path, or relative `to`). `StoresProvider` reloads when the vendor changes and keys its cache by vendor.
Remember the last vendor in `localStorage` key `hor-ingestion-vendor` (wrap in try/catch); `/` goes there.

---

## 7. Screens: what changes

| Screen | Change |
|---|---|
| Layout | Vendor switcher (two `NavLink`s or a segmented control) → `/<vendor>/<same page>` without the store/fetch-specific query; brand badge = vendor label; nav links vendor-relative; add **Departments** nav (page is in CONSOLE_PLAN §8.6 but missing in `src/`). |
| Stores | Vendor-agnostic already; sync copy "Copies the <Vendor> stores of auth_db". |
| Fetch | File-type `Field` (select) when `config.hasFileTypes`, value in `?fileType=` (default = the default type); pass it to available/loaded days and start fetch; timezone fallback must not be Indianapolis: use the store's `timezone`, and disable fetching until the store is known; copy "CPJ zip" → "`<fileType>` zip". |
| Fetch detail | Poll while `isOpen(status)` (not only RUNNING); render `zip.files` when present; render `sections` generically (row-count table + checks table, hard/soft badge, expected/actual formatted with `formatNumber`, money-looking values with 2 decimals); show `fileType`. |
| History | Column **File type** (Gilbarco); file-type filter (`All` + types); status filter list includes `CLAIMED`. |
| Departments | Columns: Dept # · Name ("— not named yet" when null) · Sales class badge (Merchandise / Lottery sale / Lottery payout / Fuel) · History (only when `config.hasDeptHistory`). Note: "The class is set from the department name by the service." |
| StatusBadge / format | `CLAIMED` → badge-info, live, label "Queued"; `formatSalesClass()`; error texts of §2.3; `BACKEND_DOWN` names the current vendor's service and port. |

---

## 8. Files

| Action | File |
|---|---|
| change | `vite.config.js` (§3.1) |
| new | `src/vendors/vendors.js`, `src/vendors/VendorProvider.jsx`, `src/vendors/LegacyRedirect.jsx` |
| new | `src/api/gilbarcoApi.js`, `src/api/ingestionApi.js` |
| change | `src/App.jsx`, `src/ui/Layout.jsx`, `src/session/StoresProvider.jsx` (use `useVendor().api`) |
| change | `src/pages/StoresPage.jsx`, `FetchPage.jsx`, `FetchDetailPage.jsx`, `HistoryPage.jsx` (use normalized api + vendor links) |
| new | `src/pages/DepartmentsPage.jsx` (both vendors) |
| change | `src/ui/StatusBadge.jsx`, `src/ui/format.js`, `src/ui/StorePicker.jsx` (typedef import only) |
| change | `docs/RUNNING_LOCALLY.md` (start gilbarco on 8093; Gilbarco checklist), `CLAUDE.md` key facts (second backend) |

---

## 9. Build order (each step ends with `npm run lint && npm run build` green)

1. Proxy + `gilbarcoApi.js` (raw) + `ingestionApi.js` (both adapters).
2. Vendor registry/provider, routes, legacy redirects, Layout switcher. Verifone screens must still work unchanged.
3. Stores + Fetch (file type) for both vendors.
4. Fetch detail (sections, CLAIMED polling) + History (file type column/filter).
5. Departments page (both vendors).
6. Docs and the acceptance checklist (§10) by hand.

---

## 10. Acceptance checklist (manual, both services + hor-auth running)

- [ ] `/admin/` → last vendor (first time Verifone); old `/admin/history?storeId=1` lands on `/admin/verifone/history?storeId=1`.
- [ ] Switching vendor reloads the store list; no Verifone store appears under Gilbarco and vice versa.
- [ ] Gilbarco **Sync stores** shows the summary; Wolf `800_NY-13208` listed with timezone `America/New_York`.
- [ ] Gilbarco Fetch, October 2026, PJR: **Check S3** marks 2026-10-09 … (2026-10-11 absent); future days disabled.
- [ ] Fetch 2026-10-15 REFUSE: detail shows Queued/Running, then **Loaded**, zip key, 974 files, row counts, 6 checks all passed.
- [ ] Same day REFUSE again → **Refused**; REPLACE (confirm modal) → Loaded again.
- [ ] Clicking Fetch twice fast → second shows `FETCH_RUNNING` message.
- [ ] History (Gilbarco) lists the fetches with File type PJR; filters by store, dates, file type; paging works.
- [ ] Departments (Gilbarco, Wolf) shows 5 LOTTO SCRATCH OFF / 6 LOTTO PAID OUT / 57 LOTTO ONLINE with lottery classes.
- [ ] Stop gilbarco-ingestion-service → Gilbarco pages show `BACKEND_DOWN` naming 8093; Verifone pages still work.
- [ ] Every Verifone check of `docs/RUNNING_LOCALLY.md` still passes.
- [ ] 375 px wide: header (with switcher) wraps, no sideways scroll; dark theme fine.
- [ ] `npm run lint` and `npm run build`: zero warnings.

---

## 11. Out of scope

Editing departments; fetching date ranges or bulk; Gilbarco report screens (`report.*` views: day summary, payments,
fuel, voids); FGM/MSM/MCM screens (they appear automatically in the file-type list once the service adds them; their
detail renders through the generic sections); production nginx; a test runner.
