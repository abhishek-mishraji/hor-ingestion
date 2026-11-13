# ingestion-console: implementation plan (authoritative, build in one go)

Written 2026-10-22. Folder: `C:\Users\abhia\OneDrive\HOR-Software\hor-stores\stores-ingestion\ingestion-console`.
This plan replaces the earlier draft. Wherever this plan and the backend code disagree, the backend wins. Report
the difference; do not change the backend.

**Readers:** the AI or engineer building the console. Everything you need is in this file. The backend docs are
linked only so you can check a detail.

---

## 0. Hard rules

1. **Work only inside `ingestion-console/`.** Do not edit `../verifone-ingestion-service`, `../gilbarco-ingestion-service` or
   `../../hor-auth`. You may **read** them, and you may **copy** files out of
   `../../hor-auth/web/auth-console/src` into this app (§4).
2. **JavaScript + JSX only.** No TypeScript, no `.ts`/`.tsx`, no `tsconfig`. Document types with JSDoc.
3. **No UI framework.** No Tailwind, MUI, Bootstrap, shadcn, chart library or date library. Use plain CSS with tokens and the
   native `<dialog>`. The only runtime dependencies are `react`, `react-dom` and `react-router`.
4. **The JS never reads or stores a token.** The cookies are HttpOnly. Never put a token or password in `localStorage`,
   `sessionStorage` or `console.log`. Only the theme choice may go in `localStorage`.
5. **Every request is same-origin through the Vite proxy** (§3). Do not enable or depend on CORS, and never call
   `http://127.0.0.1:8092` or `:8091` from browser code.
6. **Every write sends `X-HOR-Request: 1`.** For simplicity, send it on every request, as auth-console does.
7. **The server decides.** Show its problem-detail `code` and `detail`. Do not re-implement backend rules beyond the
   convenience checks this plan lists, such as disabling future days.
8. **Done means:** `npm install`, `npm run lint` (oxlint, `--deny-warnings`) and `npm run build` all pass, and the
   manual checks in §13 work against the running backends.

---

## 1. What the app is

The admin UI for store ingestion. A platform admin logs in, syncs the Verifone stores, picks a store and a day,
fetches that day's CPJ zip from S3 into `verifone_db`, watches its progress, and browses history and departments.

- **Users:** `PLATFORM_ADMIN` only. Any other role can log in, but it sees a "no access" screen with a Log out button.
- **Login: this app has its own login page** at `/admin/login`. It calls hor-auth `POST /api/auth/login` through the
  proxy and does **not** redirect to auth-console. (Cookies ignore ports, so on `localhost` the session is shared
  with auth-console on 5180. Logging in or out in one app does the same in the other. That is expected.)
- **Backend:** verifone-ingestion-service `/api/ingestion/verifone` (port 8092). Auth: hor-auth `/api/auth` (port 8091).

Screens: Login · Stores (+ Sync) · Fetch (month calendar, then fetch a day) · Fetch detail (live progress) · History ·
Departments · Not found.

---

## 2. Setup (exact files)

### 2.1 `package.json`

```json
{
  "name": "ingestion-console",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "lint": "oxlint --deny-warnings"
  },
  "dependencies": {
    "react": "^19.2.0",
    "react-dom": "^19.2.0",
    "react-router": "^7.18.4"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "^6.1.2",
    "oxlint": "^1.89.0",
    "vite": "^8.3.4"
  }
}
```

These are the same versions auth-console uses. They are known to install on this machine.

### 2.2 `vite.config.js`

```js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Served under /admin/ (auth-console links to http://localhost:5182/admin/; production apps.handsoffretail.com/admin/).
// Both APIs are proxied so every call is same-origin: the services enable no CORS and the cookies are host-only.
// Targets use 127.0.0.1, not localhost: the services bind 127.0.0.1 only, and Node may resolve localhost to ::1.
export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  server: {
    port: 5182,
    strictPort: true,
    proxy: {
      '/api/auth': { target: 'http://127.0.0.1:8091', changeOrigin: false },
      '/api/ingestion': { target: 'http://127.0.0.1:8092', changeOrigin: false },
    },
  },
});
```

`changeOrigin: false` keeps the browser's `Origin: http://localhost:5182`. Both services allow that origin for writes.

### 2.3 Environment files

`.env.development`
```
VITE_AUTH_CONSOLE_URL=http://localhost:5180/
```
`.env.production`
```
VITE_AUTH_CONSOLE_URL=https://auth.handsoffretail.com/
```
This is used only for the header link "Auth Console ↗". It has nothing to do with login.

### 2.4 `.oxlintrc.json`: copy it from auth-console unchanged

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react"],
  "rules": {
    "no-unused-vars": "error",
    "no-undef": "error",
    "react/jsx-key": "error",
    "react/jsx-no-duplicate-props": "error",
    "react/set-state-in-effect": "off",
    "react/purity": "off",
    "react-hooks/exhaustive-deps": "off"
  },
  "env": { "browser": true, "es2024": true }
}
```

### 2.5 `index.html`

This is the same as auth-console's, with `<title>HOR Ingestion Console</title>`, description "Hands Off Retail store ingestion console", the
Inter font links, `<div id="root">` and `<script type="module" src="/src/main.jsx">`.

### 2.6 `.gitignore`

```
node_modules/
dist/
*.local
```

---

## 3. Networking and security (read this before writing `api/`)

| Topic | Rule |
|---|---|
| Browser URL | Open **`http://localhost:5182/admin/`**, not `127.0.0.1`. hor-auth allows only `http://localhost:5182` as an Origin for login. |
| Base URLs in code | Relative: `/api/auth/...` and `/api/ingestion/verifone/...`. Vite proxies them in development and nginx does in production. |
| Credentials | `credentials: 'same-origin'`. The requests are same-origin through the proxy, so `include` is unnecessary. |
| Cookies | `hor_access` (JWT, 15 min, Path `/`) and `hor_refresh` (12 h, Path `/api/auth`). Both are HttpOnly and Secure. Browsers accept Secure cookies on `http://localhost`. |
| CSRF | Every request sends `X-HOR-Request: 1`. Writes without it get `403 CSRF_REJECTED`. |
| Errors | RFC 9457 `application/problem+json`: `{status, title, detail, code, errors?: [{field, message}]}`. Some also have extra properties, for example `lockedUntil` on `423`. |
| 401 | `UNAUTHENTICATED` or `TOKEN_EXPIRED`. Refresh once under a cross-tab lock, then retry. If the refresh answers `401 SESSION_ENDED`, go to this app's `/admin/login?return=<path>`. |
| 403 `FORBIDDEN` | The account is logged in but is not `PLATFORM_ADMIN`. Show the no-access screen; do not loop to login. |
| Admin single session | A new admin login anywhere ends that admin's other sessions. The old browser's next refresh fails, which sends it to login. No special code is needed. |

> Note: the project `CLAUDE.md` says the backend is at `http://127.0.0.1:8092` and to use `credentials: "include"`.
> Those are the **server** addresses. Browser code must use the relative proxied paths above. The service has no
> CORS, so a direct call would fail.

---

## 4. Folder structure

```
ingestion-console/
├── CLAUDE.md, docs/CONSOLE_PLAN.md
├── package.json, vite.config.js, index.html, .oxlintrc.json, .env.development, .env.production, .gitignore
└── src/
    ├── main.jsx                      BrowserRouter basename="/admin"
    ├── App.jsx                       routes (§6)
    ├── index.css                     COPY of auth-console's index.css + additions in §10
    ├── api/
    │   ├── client.js                 request(), ApiError, refresh lock, query strings (§5.1)
    │   ├── authApi.js                login, logout, getMe (§5.2)
    │   └── verifoneApi.js            one function per ingestion endpoint (§5.3)
    ├── session/
    │   ├── SessionProvider.jsx       COPY from auth-console, then adapt (§5.4)
    │   └── RequireAdmin.jsx          guard: not logged in → login; not admin → NoAccess (§5.4)
    ├── hooks/
    │   ├── useLoad.js                COPY from auth-console unchanged
    │   ├── useAction.js              COPY from auth-console unchanged
    │   └── usePolling.js             NEW (§8.4)
    ├── ui/
    │   ├── Field.jsx, Modal.jsx, Pager.jsx, Table.jsx, ErrorBanner.jsx    COPY unchanged
    │   ├── Layout.jsx                COPY, then adapt (§7)
    │   ├── StatusBadge.jsx           REWRITE for fetch / store statuses (§9)
    │   ├── StorePicker.jsx           NEW: <select> of stores, value = storeId (§9)
    │   ├── MonthCalendar.jsx         NEW (§8.3)
    │   ├── ConfirmReplaceModal.jsx   NEW (§8.3)
    │   ├── NoAccess.jsx              NEW (§5.4)
    │   └── format.js                 COPY, then trim and extend (§11)
    ├── lib/
    │   └── dates.js                  NEW: pure YYYY-MM-DD helpers (§11)
    └── pages/
        ├── LoginPage.jsx
        ├── StoresPage.jsx
        ├── FetchPage.jsx
        ├── FetchDetailPage.jsx
        ├── HistoryPage.jsx
        ├── DepartmentsPage.jsx
        └── NotFoundPage.jsx
```

When copying from `../../hor-auth/web/auth-console/src/`, remove everything specific to auth-console: admin, user and audit
labels, `formatStoreRole`, `formatUserType`, and the portal links. Keep the look the same. These apps are siblings.

Patterns to keep from auth-console:

- **Pages are containers.** They load with `useLoad` / `useAction` and hold the route and query state.
- **`ui/` components get props only.** They never call the API.
- Use `useSearchParams` for filter state (history filters, selected store, month), so reloading the page and the Back button keep it.

---

## 5. API layer

### 5.1 `src/api/client.js` (write it like this)

```js
/**
 * @file client.js
 * The one fetch wrapper. Same-origin cookies (hor_access / hor_refresh, HttpOnly: JS never sees them),
 * X-HOR-Request: 1 on every call, RFC 9457 errors as ApiError, and one serialized refresh on 401.
 */

export class ApiError extends Error {
  /**
   * @param {number} status
   * @param {string} code      problem `code` (e.g. FETCH_RUNNING); falls back to a status-based code
   * @param {string} message   problem `detail` (or `title`)
   * @param {Array<{field: string, message: string}>} [fieldErrors]
   * @param {object|null} [raw] the whole problem body (e.g. raw.lockedUntil)
   */
  constructor(status, code, message, fieldErrors = [], raw = null) {
    super(message || code || `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code || '';
    this.fieldErrors = Array.isArray(fieldErrors) ? fieldErrors : [];
    this.raw = raw;
  }
}

const LOGIN_PATH = `${import.meta.env.BASE_URL}login`; // "/admin/login"
const NO_REFRESH = ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout'];

async function toApiError(res) {
  let body = null;
  try {
    const text = await res.text();
    if (text) body = JSON.parse(text);
  } catch {
    // not JSON (e.g. proxy error page)
  }
  const fallback = { 401: 'UNAUTHENTICATED', 403: 'FORBIDDEN', 404: 'NOT_FOUND', 502: 'BACKEND_DOWN', 503: 'BACKEND_DOWN', 504: 'BACKEND_DOWN' };
  return new ApiError(res.status, body?.code || fallback[res.status] || 'ERROR',
    body?.detail || body?.title || res.statusText, body?.errors, body);
}

function send(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set('X-HOR-Request', '1');
  if (typeof options.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return fetch(path, { ...options, headers, credentials: 'same-origin' });
}

/** Sends the browser to this app's login page, remembering where it was. */
export function goToLogin() {
  const here = window.location.pathname + window.location.search;
  if (window.location.pathname.startsWith(LOGIN_PATH)) return;
  const inApp = here.startsWith(import.meta.env.BASE_URL) ? here.slice(import.meta.env.BASE_URL.length - 1) : '/';
  window.location.assign(`${LOGIN_PATH}?return=${encodeURIComponent(inApp)}`);
}

/** 401 → inside the cross-tab lock: retry once (another tab may have refreshed), else refresh, then retry once. */
async function refreshAndRetry(retry) {
  const run = async () => {
    const probe = await retry();
    if (probe.status !== 401) return probe;
    const refreshed = await send('/api/auth/refresh', { method: 'POST' });
    if (refreshed.ok) return retry();
    if (refreshed.status === 401) goToLogin();
    throw await toApiError(refreshed);
  };
  return navigator.locks?.request ? navigator.locks.request('hor-refresh', run) : run();
}

/**
 * @param {string} path  relative, e.g. "/api/ingestion/verifone/stores"
 * @param {RequestInit} [options]
 * @returns {Promise<any>} parsed JSON, text, or null for 204
 */
export async function request(path, options = {}) {
  const execute = () => send(path, options);
  let res;
  try {
    res = await execute();
    if (res.status === 401 && !NO_REFRESH.some((p) => path.startsWith(p))) {
      res = await refreshAndRetry(execute);
    }
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Is it running?');
  }
  if (!res.ok) throw await toApiError(res);
  if (res.status === 204) return null;
  const type = res.headers.get('content-type') || '';
  return type.includes('json') ? res.json() : res.text();
}

/** {a: 1, b: undefined} → "?a=1" (skips undefined, null and ""). */
export function query(params = {}) {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}
```

The lock name `hor-refresh` is the same one auth-console uses. That matters: both apps share the refresh cookie, and
two tabs must never rotate it at the same time.

### 5.2 `src/api/authApi.js` (hor-auth, `AUTH_SPEC.md` §8.1)

| Function | Call | Returns / errors |
|---|---|---|
| `login({email, password})` | `POST /api/auth/login` body `{email, password}` | `200 Me`. `401 BAD_CREDENTIALS` · `403 ACCOUNT_DISABLED` · `423 ACCOUNT_LOCKED` (`raw.lockedUntil`, ISO instant) · `400 VALIDATION_FAILED` |
| `logout()` | `POST /api/auth/logout` | `204` (also without a session) |
| `getMe()` | `GET /api/auth/me` | `200 Me`, or 401 after refresh fails |

`Me` (JSDoc typedef in `authApi.js`):
```json
{ "accountType": "ADMIN", "id": 7, "email": "a@b.com", "fullName": "Platform Administrator",
  "role": "PLATFORM_ADMIN", "allStores": true, "stores": [], "passwordChangedAt": "…", "lastLoginAt": "…" }
```

**Important:** `SessionProvider` calls `getMe()` on start. When nobody is logged in, the refresh fails, and `client.js`
would then redirect to login. That is fine on protected pages. To avoid a redirect loop on `/login` itself,
`goToLogin()` returns early when the browser is already on the login path (see the code above).

### 5.3 `src/api/verifoneApi.js` (verifone-ingestion-service, base `/api/ingestion/verifone`)

The JSON shapes below are taken from the service's controllers (`StoreController`, `DayController`, `FetchController`,
`DepartmentController`, `StoreSyncService`). Fields that are `null` are **left out** of the JSON
(`default-property-inclusion=non_null`), so always read optional fields defensively.

| Function | Call | Response |
|---|---|---|
| `listStores()` | `GET /stores` | `Store[]` |
| `syncStores()` | `POST /stores/sync` | `SyncResult` |
| `getAvailableDays(storeId, year, month)` | `GET /stores/{id}/available-days?year&month` (month 1–12) | `string[]` of `"YYYY-MM-DD"` (days with a CPJ zip in S3). **This can be slow: it lists S3.** |
| `getLoadedDays(storeId, from, to)` | `GET /stores/{id}/loaded-days?from&to` (≤ 400 days apart) | `LoadedDay[]` |
| `startFetch({storeId, businessDate, mode})` | `POST /fetches` | `202 {fetchRequestId}` |
| `getFetch(id)` | `GET /fetches/{id}` | `FetchDetail` |
| `listFetches({storeId, from, to, limit, offset})` | `GET /fetches?…` (limit 1–500, default 50) | `Page<Fetch>` |
| `listDepartments(storeId)` | `GET /stores/{id}/departments` | `Department[]` |

Types (write them as JSDoc typedefs at the top of `verifoneApi.js`):

```js
/** @typedef {{storeId:number, storeCode:string, storeName:string, s3Folder:string, timezone:string,
 *             status:'ACTIVE'|'INACTIVE', syncedAt:string}} Store */
/** @typedef {{inserted:number, updated:number, deactivated:number, unchanged:number,
 *             conflicts:Array<{storeId:number, storeCode:string, reason:string}>}} SyncResult */
/** @typedef {{businessDate:string, versionNo:number, files:number, daySeqs:number[], loadedAt:string,
 *             fetchRequestId:number}} LoadedDay */
/** @typedef {'RUNNING'|'LOADED'|'REFUSED'|'NOT_FOUND'|'FAILED'} FetchStatus */
/** @typedef {{id:number, storeId:number, businessDate:string, mode:'REFUSE'|'REPLACE', status:FetchStatus,
 *             message?:string, requestedBy:number, requestedAt:string, finishedAt?:string, durationMs?:number}} Fetch */
/** @typedef {{bucket:string, key:string, version:number, etag:string, sizeBytes:number, lastModified:string,
 *             manifestCreatedAt?:string, mirrorVersion?:string}} Zip */
/** @typedef {{name:string, expected?:number, actual?:number, passed:boolean, hard:boolean, detail?:string}} LoadCheck */
/** @typedef {{cpjFileId:number, filePath:string, daySeq:number, loadedAt:string,
 *             rows:Object<string,number>, checks:LoadCheck[]}} CpjFile */
/** @typedef {{fetch:Fetch, zip?:Zip, files?:CpjFile[]}} FetchDetail */
/** @typedef {{items:Fetch[], total:number, limit:number, offset:number}} FetchPage */
/** @typedef {{name:string, deptType?:string, firstSeen?:string, lastSeen?:string}} DeptName */
/** @typedef {{deptNumber:number, currentName:string, names:DeptName[],
 *             lotteryClass?:'LOTTERY_SALE'|'LOTTERY_PAYOUT'}} Department */
```

Notes:
- A `Fetch` has `storeId` but no store code or name. Join it with the `listStores()` result on the client.
- `requestedBy` is the admin's numeric id. Show it as `#7`. The API does not return admin names.
- `rows` maps a table name to a row count (for example `{"cpj.transactions": 812, …}`). Show it as a two-column table sorted by name.
- Ingestion error codes: `404 NOT_FOUND` (unknown store or fetch; "Run Sync stores"), `409 FETCH_RUNNING`,
  `409 FETCH_QUEUE_FULL`, `409 STORE_INACTIVE`, `422 STORE_NOT_VERIFONE`, `422 DATE_IN_FUTURE`, `422 BAD_RANGE`,
  `422 BAD_LIMIT`, `422 BAD_OFFSET`, `400 VALIDATION_FAILED`, `403 FORBIDDEN`, `403 CSRF_REJECTED`.

There is no vendor dispatcher yet: every store in this service is Verifone. Keep `api/` per vendor so a later
`gilbarcoApi.js` can sit next to `verifoneApi.js`.

### 5.4 Session and guards

**`SessionProvider.jsx`** (copied from auth-console, then adapted):
- Provides `{ me, ready, setMe, refreshMe, logout }`.
- On mount it calls `getMe()`. On success it sets `me`. On any error it sets `me = null`. Either way it ends with `ready = true`.
- `logout()`: `await authApi.logout()` (ignore errors), then `setMe(null)` and `navigate('/login', { replace: true })`. Use
  `useNavigate` here, because the provider sits inside `BrowserRouter`. Do not hard-code `/login` in `window.location`; that would drop the `/admin` base.

**`RequireAdmin.jsx`**:
- If `!ready`, show a centred spinner with "Loading session…".
- If `!me`, `<Navigate to={`/login?return=${encodeURIComponent(pathname + search)}`} replace />`. Skip `return` when the path is `/`.
- If `me.role !== 'PLATFORM_ADMIN'`, render `<NoAccess />`.
- Otherwise render `children ?? <Outlet/>`.

**`NoAccess.jsx`** is a card: "🔒 You do not have access. The ingestion console is for platform administrators. You are signed in as
{fullName} ({role})." It has two buttons: **Log out** (from the session) and **Auth Console ↗** (`VITE_AUTH_CONSOLE_URL`, new tab).
Also show this card when any page gets `ApiError` with `status === 403 && code === 'FORBIDDEN'`.

---

## 6. Routes (`App.jsx`, all under basename `/admin`)

```jsx
<SessionProvider>
  <Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route element={<RequireAdmin><Layout /></RequireAdmin>}>
      <Route index element={<Navigate to="/stores" replace />} />
      <Route path="/stores" element={<StoresPage />} />
      <Route path="/fetch" element={<FetchPage />} />               {/* ?storeId=&month=YYYY-MM */}
      <Route path="/fetches/:id" element={<FetchDetailPage />} />
      <Route path="/history" element={<HistoryPage />} />           {/* ?storeId=&from=&to=&offset=&limit= */}
      <Route path="/departments" element={<DepartmentsPage />} />   {/* ?storeId= */}
      <Route path="*" element={<NotFoundPage />} />
    </Route>
  </Routes>
</SessionProvider>
```

`main.jsx`: `createRoot(...).render(<StrictMode><BrowserRouter basename="/admin"><App/></BrowserRouter></StrictMode>)`.

**StrictMode runs effects twice in development.** `useLoad` and `usePolling` must clean up on unmount (an `active`
flag, `clearTimeout`), so a doubled effect neither shows stale data nor starts two polls. Starting a fetch happens only on a
button click, never in an effect.

### Store list shared across pages

FetchPage, HistoryPage and DepartmentsPage all need the store list. Put a small `StoresProvider` in `Layout`, or a
`useStores()` hook that caches it at module level. It loads `listStores()` once and exposes `{stores, byId, loading, error, reload}`.
StoresPage calls `reload()` after a sync. This is the only shared cache. Do not add a state library.

---

## 7. Layout (header + page frame)

Copy auth-console's `Layout.jsx`, then change:
- Brand: logo "H", title **HOR Ingestion**, badge **Verifone**.
- Nav (`NavLink`): **Stores** `/stores` · **Fetch** `/fetch` · **History** `/history` · **Departments** `/departments` ·
  **Auth Console ↗** (`VITE_AUTH_CONSOLE_URL`, `target="_blank" rel="noopener noreferrer"`).
- Right side: theme toggle (same `hor-theme` key and behaviour as auth-console), `me.fullName` + role badge, **Log out** button.
- Remove the `/account` link and the portal links.
- Phone width: the nav wraps, and nothing scrolls sideways.

---

## 8. Screens

Every page has the same frame: a page title (`<h1>`), a one-line description, an `ErrorBanner` for load and action errors,
then the content. While loading, show a spinner or the table's loading row. Empty lists show the `Table` empty state with a
message that tells the user what to do.

### 8.1 Login (`/login`)

Copy auth-console's `LoginPage.jsx` and change:

- Branding: "HOR Ingestion" / "Sign in to the store ingestion console".
- Fields: Email (`type=email`, `autoComplete=username`) and Password (`type=password`, `autoComplete=current-password`). Submit is
  disabled until both are non-empty and while the request is running ("Signing in…" with a spinner). The form is `noValidate`; the server validates.
- On submit, call `authApi.login`. On success: `setMe(user)`, then `navigateAfterLogin(user)`.
- `navigateAfterLogin`: take the `return` query parameter only if it starts with `/`, does not start with `//`, and does not contain
  `/login`. Otherwise use `/stores`. Then `navigate(target, {replace: true})` with react-router (the path is relative to the basename).
  Non-admins also go to the target; `RequireAdmin` then shows `NoAccess`. Do **not** accept absolute URLs; this app needs none.
- Already logged in (`ready && me`): redirect at once, the same way.
- Error messages (`format.js`):
  - `BAD_CREDENTIALS`: "The email or password is incorrect."
  - `ACCOUNT_LOCKED`: "Too many wrong passwords. The account is locked until {local time of raw.lockedUntil}."
    If `lockedUntil` is missing, show "…locked for 15 minutes."
  - `ACCOUNT_DISABLED`: "This account is disabled. Ask a platform administrator."
  - `VALIDATION_FAILED`: show the field errors under the fields.
  - `NETWORK` / `BACKEND_DOWN`: "Cannot reach the sign-in service (hor-auth on port 8091). Is it running?"
- Clear the password field after a failed login. Keep the email.
- Small print under the form: "Signing in here also signs you in to other HOR consoles in this browser."

### 8.2 Stores (`/stores`)

Purpose: see the synced Verifone stores and run **Sync stores**.

- Header row: title "Stores" and, on the right, a primary button **Sync stores** that calls `syncStores()` through `useAction`, with a spinner while it runs.
- After the sync succeeds, show a result panel (a success banner plus a small grid):
  `Inserted N · Updated N · Deactivated N · Unchanged N`. If `conflicts.length > 0`, show a warning panel "N store(s) could not be
  synced" with a table (Store ID, Store code, Reason). Then reload the store list.
- Table columns:

| Column | Value |
|---|---|
| Code | `storeCode` (monospace) |
| Name | `storeName` |
| Timezone | `timezone` |
| S3 folder | `s3Folder` (monospace, small, truncated with a `title` tooltip) |
| Status | `StatusBadge` ACTIVE/INACTIVE |
| Synced | `formatDateTime(syncedAt)` |
| Actions | links **Fetch** → `/fetch?storeId=ID`, **History** → `/history?storeId=ID`, **Departments** → `/departments?storeId=ID`. Hide Fetch for INACTIVE stores. |

- Empty list: "No stores yet. Click **Sync stores** to copy the Verifone stores from hor-auth."
- Help text under the title: "Stores come from hor-auth. A change there (new store, name, S3 folder, deactivation) appears here only after Sync stores."

### 8.3 Fetch (`/fetch?storeId=&month=YYYY-MM`)

Purpose: choose a day and load it.

**Top bar:**
- `StorePicker`, with ACTIVE stores only and the label "Code: Name". Changing it updates `storeId` in the URL. If no `storeId` is given and only
  one active store exists, choose it automatically.
- Month navigation: `‹ Prev` · "September 2026" · `Next ›` and a **Today** button. Next is disabled after the current month in the
  store's timezone. The month lives in the URL. The default is the current month in the store's timezone.

**Data per (store, month):** run both loads in parallel with `Promise.allSettled`, so one failure does not hide the other:
- `getAvailableDays(storeId, y, m)`: the S3 days. Show a "Checking S3…" spinner over the calendar. If it fails, show the
  error and still show the loaded days.
- `getLoadedDays(storeId, firstOfMonth, lastOfMonth)`: index the result by `businessDate`.

**`MonthCalendar`** (presentational) is a 7-column grid, Sunday first, with weekday headers. Each day cell is a `<button>` with an
`aria-label` that gives the date and its state. Cell states:

| State | Rule | Look | Clickable |
|---|---|---|---|
| Future | date > today in the store's timezone | faded | no |
| Loaded | in loaded-days | blue (accent) fill, ✓, small "v{versionNo}" | yes |
| Available | in S3 and not loaded | green outline, ● | yes |
| Missing | not in S3, not loaded, not future | grey, "–" | yes (the fetch will end NOT_FOUND, which is useful to confirm) |
| Selected | the chosen day | thick focus ring | — |

A legend under the grid explains the four colours. Also show a month summary: "S3: 28 days · Loaded: 20 · Ready to load: 8".

**Day panel:** a side panel on wide screens, below the calendar on phones. It is shown once a day is selected:
- The date in long form ("Tuesday, 8 September 2026").
- S3: "Zip available" or "No zip in S3 for this day".
- Loaded: "Loaded v2 · 1 file · day seq 1 · loaded {datetime} · from fetch #123" (link to `/fetches/123`), or "Not loaded".
- Mode radio group (`fieldset` + `legend` "If this day is already loaded"):
  - **REFUSE (safe)**, the default: "Do nothing if the day is already loaded."
  - **REPLACE (destructive)**: "Delete the loaded data for this day and load it again."
- Button **Fetch day**. With REPLACE it opens `ConfirmReplaceModal` first.
- `ConfirmReplaceModal`: title "Replace {date}?", body "This deletes everything loaded for {storeCode} on {date} and loads it again from
  S3. This cannot be undone." The user must **type the date (YYYY-MM-DD)** to enable the red button **Replace day**. Cancel and Esc close it.
- On submit, call `startFetch({storeId, businessDate, mode})`. On `202`, `navigate('/fetches/' + fetchRequestId)`.
- Errors stay in the panel:
  - `FETCH_RUNNING`: "A fetch of this day is already running." Add a link to History filtered by this store.
  - `FETCH_QUEUE_FULL`: "Too many fetches are waiting; try again in a minute."
  - `STORE_INACTIVE`, `DATE_IN_FUTURE`, `NOT_FOUND` (+ "Run Sync stores"): use the server `detail`.

### 8.4 Fetch detail / live progress (`/fetches/:id`)

- Load `getFetch(id)`. While `fetch.status === 'RUNNING'`, **poll every 2 s** with `usePolling`.
  - `usePolling(fn, intervalMs, shouldContinue)`: run `fn` once, then `setTimeout` again after each answer. Use a timeout chain,
    never `setInterval`, so slow answers cannot pile up. Stop when `shouldContinue(data)` is false, on unmount, or after 3 errors in a row.
    While `document.hidden`, wait instead of polling, and resume on `visibilitychange`.
  - When the fetch leaves RUNNING, stop and show the final state.
- **Header card:** "Fetch #ID", big `StatusBadge`, store "{code}: {name}" (from the store list), business date, mode badge
  (REPLACE in red), requested by `#id`, requested at, finished at, duration. While RUNNING, show **elapsed time** that ticks every second
  on the client (now − `requestedAt`) and a "Live" indicator.
- `message` (when present): a callout styled by status. FAILED / NOT_FOUND are danger, REFUSED is warning, LOADED is info.
  Show it in full with `white-space: pre-wrap`.
- **Zip card** (when `zip` is present): bucket, key (monospace, with a copy button using `navigator.clipboard`), version, size (`formatBytes`), ETag,
  last modified, manifest created, mirror version.
- **Files** (one section per `files[]` item): file path, day seq, loaded at.
  - **Rows table**: table, count right-aligned with thousand separators, plus a total row.
  - **Load checks table**: Check name, Expected, Actual, Hard (yes/no), Result (✓ pass in green / ✗ fail in red), Detail. Sort failed checks
    first. Over the table, show a summary "9 of 9 checks passed".
- Buttons: **Back to calendar** → `/fetch?storeId=…&month=<month of businessDate>`, and **History for this store**.
- `404`: "Fetch #ID not found."

### 8.5 History (`/history?storeId=&from=&to=&offset=&limit=`)

- Filters row: `StorePicker` with "All stores" (empty value) and every store, inactive ones included, plus **From** and **To** (`<input type="date">`), **Apply**
  and **Reset**. The filters live in the URL. Changing a filter resets `offset` to 0.
- `listFetches({storeId, from, to, limit, offset})`. The default limit is 50; the Pager offers 25/50/100/200.
- Table columns: ID (link to `/fetches/:id`) · Store code (joined) · Business date · Mode · Status badge · Requested at · Duration
  (`formatDuration(durationMs)`, "running…" if RUNNING) · Message (one line, truncated with `text-overflow: ellipsis`).
- **Expandable message:** clicking a row (or a ▸ button with `aria-expanded`) opens a full-width row under it with the full `message`
  (`pre-wrap`) and a link "Open details".
- If any row on the page is RUNNING, reload the page of results every 5 s until none is.
- `BAD_RANGE`, `BAD_LIMIT`, `BAD_OFFSET`: show them in the banner.
- Empty list: "No fetches match these filters."

### 8.6 Departments (`/departments?storeId=`)

- `StorePicker` (all stores). Until a store is chosen, show "Choose a store."
- `listDepartments(storeId)`. Table columns: Dept # (right-aligned) · Current name · Lottery class (badge: "Lottery sale" / "Lottery payout" / "—") ·
  Names (count; ▸ expands a sub-table **Name, Type, First seen, Last seen** with `formatDate`, or "never loaded" when missing).
- Read-only. The note under the title reads: "The lottery class is set from the department name by the service. It cannot be edited here."
- **Gilbarco (`canEditDepartments`):** the platform admin can set a department's name and sales class. Extra columns
  Last sold · Set by (Admin badge + "you" / "admin #id", or "POS files") · **Edit**. A department with sales but no name
  gets a "Needs a name" badge, and a callout counts them with a "Show only these" filter (scratch-off tickets are only
  scanned, so the POS never names their department and it counts as merchandise). The Edit dialog
  (`ui/DepartmentEditModal.jsx`): name (max 60), class (Merchandise / Lottery sale / Lottery payout; fuel is classified by
  its lines), optional note (max 500), a warning that totals change on every report including past days, **Return to
  automatic** when admin-set, the change history, and **What was sold** above the form (top 15 items by lines: description, scanned / dept ring, code, units, amount, last sold, plus totals; `GET /stores/{id}/departments/{n}/items`). Calls `PUT /stores/{id}/departments/{n}`,
  `DELETE /stores/{id}/departments/{n}/override`, `GET /stores/{id}/departments/{n}/changes`. Verifone stays read-only.
- A text filter (client-side) on dept number or name.

### 8.7 Not found (`*`)

"Page not found" with a link to `/stores`.

---

## 9. Shared UI components (new or rewritten)

| Component | Props | Notes |
|---|---|---|
| `StatusBadge` | `{status}` | Classes: `RUNNING` = info, pulsing dot; `LOADED` = success; `REFUSED` = warning; `NOT_FOUND` = neutral; `FAILED` = danger; `ACTIVE` = success; `INACTIVE` = neutral. Labels via `formatStatus`. `role="status"` only on the live one in the detail header. |
| `ModeBadge` | `{mode}` | REFUSE = neutral, REPLACE = danger outline. It can live in `StatusBadge.jsx`. |
| `StorePicker` | `{stores, value, onChange, includeAll?, activeOnly?, disabled?, label?}` | A `<select>` built on `Field as="select"`. The value is the id as a string. |
| `MonthCalendar` | `{year, month, available:Set<string>, loaded:Map<string,LoadedDay>, today:string, selected?:string, onSelect}` | Pure. Arrow-key movement between days is nice to have. |
| `ConfirmReplaceModal` | `{isOpen, date, storeCode, onConfirm, onClose, busy}` | Built on `Modal`. The confirm button is disabled until the typed text === `date`. |
| `NoAccess` | — | §5.4. |

---

## 10. Styling

- Copy auth-console's `index.css`. It has the tokens, dark mode, header, buttons, banners, tables, badges, dialog, pager, login card and spinner.
- Add at the end, using only the existing tokens:
  - `.badge-info`, `.badge-success`, `.badge-warning`, `.badge-danger`, `.badge-neutral`, and `.badge-live::before` (a pulsing dot;
    turn the animation off under `@media (prefers-reduced-motion: reduce)`).
  - `.calendar` (`display:grid; grid-template-columns:repeat(7,1fr)`), `.cal-day`, `.cal-day--loaded`, `--available`, `--missing`,
    `--future`, `--selected`, `.cal-legend`.
  - `.fetch-layout` (calendar + panel: `grid-template-columns: minmax(0,1fr) 340px`, a single column under 900px).
  - `.stat-grid` (sync counts, zip facts), `.mono`, `.num` (`text-align:right; font-variant-numeric: tabular-nums`), `.callout-*`.
  - `.row-expand` for the history and department expanded rows.
- Status colours must work in both themes. The meaning must also come from the text and icon (✓ ✗ ● –), never colour alone.
- At 375px wide nothing scrolls sideways except table wrappers (`overflow-x:auto` is already in `.table-wrapper`).

---

## 11. Helpers

### `src/lib/dates.js` (business dates are `"YYYY-MM-DD"` strings; never use `new Date("2026-09-08")`, which parses as UTC and shifts the day)

```js
const pad = (n) => String(n).padStart(2, '0');
export const ymd = (y, m, d) => `${y}-${pad(m)}-${pad(d)}`;
export const daysInMonth = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();      // m = 1..12
export const firstWeekday = (y, m) => new Date(Date.UTC(y, m - 1, 1)).getUTCDay(); // 0 = Sunday
export const monthRange = (y, m) => ({ from: ymd(y, m, 1), to: ymd(y, m, daysInMonth(y, m)) });
export const addMonths = (y, m, delta) => { const i = y * 12 + (m - 1) + delta; return { y: Math.floor(i / 12), m: (i % 12) + 1 }; };
export const parseMonth = (s) => { const r = /^(\d{4})-(\d{2})$/.exec(s || ''); return r ? { y: +r[1], m: +r[2] } : null; };
export const toMonthParam = (y, m) => `${y}-${pad(m)}`;
/** Today's date in an IANA timezone, e.g. todayIn('America/Indiana/Indianapolis') → "2026-10-22". */
export const todayIn = (tz) => new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
/** "2026-09-08" → "Tuesday, 8 September 2026" (built in UTC so the day never shifts). */
export const longDate = (s) => { const [y, m, d] = s.split('-').map(Number);
  return new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(new Date(Date.UTC(y, m - 1, d))); };
export const monthLabel = (y, m) => new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', month: 'long', year: 'numeric' }).format(new Date(Date.UTC(y, m - 1, 1)));
```

String comparison (`a > b`) is correct for `YYYY-MM-DD`.

### `src/ui/format.js`

Keep `formatDateTime`, `formatDate` (for timestamps only; do not pass business dates to them), `ERROR_MESSAGES` and `getFriendlyErrorMessage`. Remove the auth-console-only
helpers. Add:
- `formatDuration(ms)`: `850 ms` · `12.4 s` · `3 min 05 s`, or `—` when there is no value.
- `formatBytes(n)`: `512 B`, `1.2 MB`.
- `formatNumber(n)`: `Intl.NumberFormat('en-US')`.
- `formatStatus(s)`: RUNNING "Running", LOADED "Loaded", REFUSED "Refused", NOT_FOUND "Not in S3", FAILED "Failed", ACTIVE "Active", INACTIVE "Inactive".
- `formatLotteryClass(c)`.
- `ERROR_MESSAGES` (shown instead of `detail` when a code is listed. **Except:** for `NOT_FOUND`, `STORE_INACTIVE`, `DATE_IN_FUTURE` and `BAD_*`,
  show the server `detail`, because it is more specific):

```js
BAD_CREDENTIALS: 'The email or password is incorrect.',
ACCOUNT_DISABLED: 'This account is disabled. Ask a platform administrator.',
ACCOUNT_LOCKED: 'Too many wrong passwords. The account is locked for 15 minutes.',
SESSION_ENDED: 'Your session has ended. Please sign in again.',
FORBIDDEN: 'Only platform administrators can use the ingestion console.',
CSRF_REJECTED: 'The request was rejected by the security check. Open the console at http://localhost:5182/admin/.',
VALIDATION_FAILED: 'Some values are invalid. See the fields below.',
FETCH_RUNNING: 'A fetch of this store and day is already running.',
FETCH_QUEUE_FULL: 'Too many fetches are waiting; try again in a minute.',
STORE_NOT_VERIFONE: 'This store is not a Verifone store.',
NETWORK: 'Cannot reach the server. Is the service running?',
BACKEND_DOWN: 'The service is not responding (check that verifone-ingestion-service on 8092 and hor-auth on 8091 are running).',
```

---

## 12. Build order (each step ends with `npm run lint && npm run build` green)

| # | Step | Done when |
|---|---|---|
| 1 | Write the §2 files. Run `npm install`. Make a minimal `main.jsx`/`App.jsx` that renders "Hello". | `npm run dev` serves `http://localhost:5182/admin/` |
| 2 | Copy `index.css`, `ui/*`, `hooks/*` and the session files from auth-console and trim them (§4). Add `lib/dates.js` and `format.js`. | lint passes, no unused auth-console code |
| 3 | `api/client.js`, `authApi.js`, `verifoneApi.js` (§5) | |
| 4 | `SessionProvider`, `RequireAdmin`, `NoAccess`, `LoginPage`, `Layout`, routes | login, refresh after 15 min and logout work in the browser; a non-admin sees NoAccess |
| 5 | Stores provider + `StoresPage` with Sync | sync shows counts; the list fills |
| 6 | `MonthCalendar`, `ConfirmReplaceModal`, `FetchPage` | the calendar shows S3 days and loaded days for Tiki Hut |
| 7 | `usePolling`, `FetchDetailPage` | a fetch goes RUNNING → LOADED live with rows and 9 checks |
| 8 | `HistoryPage`, `DepartmentsPage`, `NotFoundPage` | |
| 9 | Polish: phone width, dark mode, keyboard, empty and error states. Update `CLAUDE.md` (§14). Write `docs/RUNNING_LOCALLY.md` (§13). | the §13 checklist passes |

---

## 13. Running locally and manual acceptance checks

`docs/RUNNING_LOCALLY.md` (write it in step 9):
1. Start PostgreSQL, then hor-auth (`hor-auth/services/auth-service`: `./gradlew.bat bootRun`, port 8091), then
   verifone-ingestion-service (`./gradlew.bat bootRun` from its folder, port 8092).
2. In this folder: `npm install` (first time), then `npm run dev`.
3. Open **http://localhost:5182/admin/**. Log in with the platform admin from `%USERPROFILE%\.hor-stores\secrets.properties`.
   Type it yourself; never commit it.

Checklist:

- [ ] `/admin/` with no session goes to `/admin/login`. A wrong password shows the BAD_CREDENTIALS message, and the password field is cleared.
- [ ] A correct login lands on Stores. The header shows the admin's name and badge. Reloading the page keeps you logged in.
- [ ] `/admin/history?storeId=1` without a session goes to login and then back to that exact URL after login.
- [ ] A HOR_STAFF or CLIENT_USER login sees NoAccess with a working Log out.
- [ ] Log out goes back to login. Opening `/admin/stores` again asks for login.
- [ ] Sync stores shows counts. The first run shows Tiki Hut `8730_IN-47872` as inserted; later runs show it as unchanged.
- [ ] Fetch → Tiki Hut → a month with data: S3 days are marked, loaded days show ✓ vN, future days are disabled.
- [ ] REFUSE on an unloaded available day → detail page polls → LOADED. Rows and "9 of 9 checks passed" are shown.
- [ ] REFUSE on the same day again → REFUSED with the server message.
- [ ] REPLACE needs the typed date, then reloads the day → LOADED.
- [ ] Fetching a day not in S3 → NOT_FOUND with a message.
- [ ] Two quick clicks on the same day → the second shows FETCH_RUNNING (or is blocked by the disabled button).
- [ ] History lists the fetches. Filters and the pager work. A message expands. The URL keeps the filters on reload.
- [ ] Departments shows the Tiki Hut departments, with 29 Lotto = Lottery sale and 28 Lotto Payout = Lottery payout.
- [ ] Stop the 8092 service: pages show the BACKEND_DOWN message and do not crash or redirect.
- [ ] Wait more than 15 minutes (or delete the `hor_access` cookie in devtools), then act: the request refreshes silently and works.
- [ ] Dark mode and a 375px-wide window look right. Tab order and Esc on the modal work.
- [ ] `npm run lint` and `npm run build` pass. There is no `console.log` of user data, and nothing in `localStorage` except `hor-theme`.

---

## 14. `CLAUDE.md`

`CLAUDE.md` has already been updated to match this plan (JSX, `/admin/` base, proxy, own login). When done, add a link
to `docs/RUNNING_LOCALLY.md`, and keep `CLAUDE.md` in sync if anything changes.

---

## 15. Out of scope (do not build)

- Gilbarco screens and a vendor dispatcher (later: `gilbarcoApi.js` + a vendor field on the store).
- Editing departments or lottery classes (the API has no endpoint).
- Fetching a date range, bulk fetches, or scheduled fetches (the API fetches one day per request).
- Account and password screens (they live in auth-console), creating stores (in auth-console), or per-permission UI
  (later backend phase `VERIFONE_*` permissions).
- Tests with a test runner. Lint, build and the §13 checklist are the gate for this phase.
- Production nginx config. Deploy will later serve `dist/` at `apps.handsoffretail.com/admin/` and proxy `/api/auth/` and `/api/ingestion/` on
  that host.
