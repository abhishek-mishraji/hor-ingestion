/**
 * @file client.js
 * The one fetch wrapper. Same-origin cookies (hor_access / hor_refresh, HttpOnly: JS never sees them),
 * X-HOR-Request: 1 on every call, RFC 9457 errors as ApiError, and one serialized refresh on 401.
 */

export class ApiError extends Error {
  /**
   * @param {number} status
   * @param {string} code problem `code` (e.g. FETCH_RUNNING); falls back to a status-based code
   * @param {string} message problem `detail` (or `title`)
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
  const fallback = {
    401: 'UNAUTHENTICATED',
    403: 'FORBIDDEN',
    404: 'NOT_FOUND',
    502: 'BACKEND_DOWN',
    503: 'BACKEND_DOWN',
    504: 'BACKEND_DOWN',
  };
  return new ApiError(
    res.status,
    body?.code || fallback[res.status] || 'ERROR',
    body?.detail || body?.title || res.statusText,
    body?.errors,
    body
  );
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
  const inApp = here.startsWith(import.meta.env.BASE_URL)
    ? here.slice(import.meta.env.BASE_URL.length - 1)
    : '/';
  window.location.assign(`${LOGIN_PATH}?return=${encodeURIComponent(inApp)}`);
}

/** 401 -> inside the cross-tab lock: retry once (another tab may have refreshed), else refresh, then retry once. */
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
 * @param {string} path relative, e.g. "/api/ingestion/verifone/stores"
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

/** {a: 1, b: undefined} -> "?a=1" (skips undefined, null and ""). */
export function query(params = {}) {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}
