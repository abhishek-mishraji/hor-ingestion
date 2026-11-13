/**
 * @file format.js
 * Utility formatters for timestamps, durations, numbers, statuses, and error messages.
 */

/**
 * Formats an ISO-8601 timestamp string into a readable date and time.
 * @param {string|null|undefined} isoString
 * @returns {string}
 */
export function formatDateTime(isoString) {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return new Intl.DateTimeFormat('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      timeZoneName: 'short',
    }).format(d);
  } catch {
    return isoString;
  }
}

/**
 * Formats an ISO-8601 timestamp string into a readable date only (timestamps only; do not pass business dates).
 * @param {string|null|undefined} isoString
 * @returns {string}
 */
export function formatDate(isoString) {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return new Intl.DateTimeFormat('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    }).format(d);
  } catch {
    return isoString;
  }
}

/**
 * Formats milliseconds into a readable duration: "850 ms", "12.4 s", "3 min 05 s", or "—".
 * @param {number|null|undefined} ms
 * @returns {string}
 */
export function formatDuration(ms) {
  if (ms == null || isNaN(ms)) return '—';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
  const mins = Math.floor(ms / 60000);
  const secs = Math.floor((ms % 60000) / 1000);
  return `${mins} min ${String(secs).padStart(2, '0')} s`;
}

/**
 * Formats bytes into human-readable representation: "512 B", "1.2 MB".
 * @param {number|null|undefined} bytes
 * @returns {string}
 */
export function formatBytes(bytes) {
  if (bytes == null || isNaN(bytes)) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  const gb = mb / 1024;
  return `${gb.toFixed(1)} GB`;
}

/**
 * Formats a number with comma separators: e.g. 1,234.
 * @param {number|null|undefined} n
 * @returns {string}
 */
export function formatNumber(n) {
  if (n == null || isNaN(n)) return '—';
  return new Intl.NumberFormat('en-US').format(n);
}

/**
 * Friendly label for account role.
 * @param {string} role
 * @returns {string}
 */
export function formatAccountRole(role) {
  switch (role) {
    case 'PLATFORM_ADMIN':
      return 'Platform Admin';
    case 'HOR_STAFF':
      return 'HOR Staff';
    case 'CLIENT_USER':
      return 'Client User';
    default:
      return role || '—';
  }
}

/**
 * Friendly label for fetch or store status.
 * @param {string} status
 * @returns {string}
 */
export function formatStatus(status) {
  switch (status) {
    case 'CLAIMED':
      return 'Queued';
    case 'RUNNING':
      return 'Running';
    case 'LOADED':
      return 'Loaded';
    case 'REFUSED':
      return 'Refused';
    case 'NOT_FOUND':
      return 'Not in S3';
    case 'FAILED':
      return 'Failed';
    case 'ACTIVE':
      return 'Active';
    case 'INACTIVE':
      return 'Inactive';
    default:
      return status || '—';
  }
}

/**
 * Friendly label for lottery class.
 * @param {string|null|undefined} lotteryClass
 * @returns {string}
 */
export function formatLotteryClass(lotteryClass) {
  switch (lotteryClass) {
    case 'LOTTERY_SALE':
      return 'Lottery sale';
    case 'LOTTERY_PAYOUT':
      return 'Lottery payout';
    default:
      return '—';
  }
}

/**
 * Friendly label for department sales class.
 * @param {string|null|undefined} salesClass
 * @returns {string}
 */
export function formatSalesClass(salesClass) {
  switch (salesClass) {
    case 'lottery_sale':
      return 'Lottery sale';
    case 'lottery_payout':
      return 'Lottery payout';
    case 'fuel':
      return 'Fuel';
    case 'merchandise':
      return 'Merchandise';
    default:
      return salesClass || '—';
  }
}

/**
 * Ingestion and Auth friendly error messages dictionary.
 */
export const ERROR_MESSAGES = {
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
  STORE_NOT_GILBARCO: 'This store is not a Gilbarco store.',
  NETWORK: 'Cannot reach the server. Is the service running?',
  BACKEND_DOWN: 'The service is not responding (check that the ingestion service and hor-auth on 8091 are running).',
};

/**
 * Translates an ApiError or Error into a user-friendly message, customized for the active vendor if provided.
 * @param {any} error
 * @param {import('../vendors/vendors.js').VendorConfig} [vendorConfig]
 * @returns {string}
 */
export function getFriendlyErrorMessage(error, vendorConfig) {
  if (!error) return '';
  if (typeof error === 'string') return error;

  const code = error.code;
  const detail = error.message;

  // Specific errors where backend detail is more descriptive than fixed text:
  if (
    code === 'NOT_FOUND' ||
    code === 'STORE_INACTIVE' ||
    code === 'DATE_IN_FUTURE' ||
    code === 'UNSUPPORTED_FILE_TYPE' ||
    (code && code.startsWith('BAD_') && code !== 'BAD_CREDENTIALS')
  ) {
    return detail || ERROR_MESSAGES[code] || 'Request failed';
  }

  // Backend service down with vendor-aware message:
  if (code === 'BACKEND_DOWN') {
    if (vendorConfig?.service && vendorConfig?.port) {
      return `The service is not responding (check that ${vendorConfig.service} on ${vendorConfig.port} and hor-auth on 8091 are running).`;
    }
    return ERROR_MESSAGES.BACKEND_DOWN;
  }

  // Vendor-aware fetch running message:
  if (code === 'FETCH_RUNNING') {
    if (vendorConfig?.hasFileTypes) {
      return 'A fetch of this store, day and file type is already running.';
    }
    return ERROR_MESSAGES.FETCH_RUNNING;
  }

  // Account locked with lockedUntil instant:
  if (code === 'ACCOUNT_LOCKED') {
    if (error.raw?.lockedUntil) {
      return `Too many wrong passwords. The account is locked until ${formatDateTime(error.raw.lockedUntil)}.`;
    }
    return ERROR_MESSAGES.ACCOUNT_LOCKED;
  }

  if (code && ERROR_MESSAGES[code]) {
    return ERROR_MESSAGES[code];
  }

  return detail || 'An unexpected error occurred. Please try again.';
}
