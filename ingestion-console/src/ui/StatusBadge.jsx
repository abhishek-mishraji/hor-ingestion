import { formatStatus, formatAccountRole, formatSalesClass } from './format.js';

/**
 * StatusBadge component displays status badges for ingestion jobs, stores, or user roles.
 * @param {object} props
 * @param {'CLAIMED'|'RUNNING'|'LOADED'|'REFUSED'|'NOT_FOUND'|'FAILED'|'ACTIVE'|'INACTIVE'|string} [props.status]
 * @param {string} [props.role]
 * @param {boolean} [props.live] - If true, adds pulsing dot and role="status"
 * @param {string} [props.className]
 */
export function StatusBadge({ status, role, live = false, className = '' }) {
  if (role) {
    let roleClass = 'badge-role-admin';
    if (role === 'HOR_STAFF') roleClass = 'badge-role-staff';
    if (role === 'CLIENT_USER') roleClass = 'badge-role-client';
    return (
      <span className={`badge ${roleClass} ${className}`}>
        {formatAccountRole(role)}
      </span>
    );
  }

  if (!status) return null;

  let colorClass = 'badge-neutral';
  let icon = '';

  switch (status) {
    case 'CLAIMED':
    case 'RUNNING':
      colorClass = 'badge-info';
      break;
    case 'LOADED':
      colorClass = 'badge-success';
      icon = '✓ ';
      break;
    case 'REFUSED':
      colorClass = 'badge-warning';
      break;
    case 'NOT_FOUND':
      colorClass = 'badge-neutral';
      icon = '– ';
      break;
    case 'FAILED':
      colorClass = 'badge-danger';
      icon = '✗ ';
      break;
    case 'ACTIVE':
      colorClass = 'badge-success';
      break;
    case 'INACTIVE':
      colorClass = 'badge-neutral';
      break;
    default:
      colorClass = 'badge-neutral';
  }

  const isLive = live || status === 'RUNNING' || status === 'CLAIMED';
  const liveClass = isLive ? 'badge-live' : '';

  return (
    <span
      className={`badge ${colorClass} ${liveClass} ${className}`}
      {...(isLive ? { role: 'status' } : {})}
    >
      {icon}{formatStatus(status)}
    </span>
  );
}

/**
 * ModeBadge displays REFUSE or REPLACE badges.
 * @param {object} props
 * @param {'REFUSE'|'REPLACE'|string} props.mode
 * @param {string} [props.className]
 */
export function ModeBadge({ mode, className = '' }) {
  if (!mode) return null;
  const isReplace = mode === 'REPLACE';
  const badgeClass = isReplace ? 'badge-mode-replace' : 'badge-mode-refuse';

  return (
    <span className={`badge ${badgeClass} ${className}`}>
      {mode}
    </span>
  );
}

/**
 * SalesClassBadge displays the department sales class badge.
 * @param {object} props
 * @param {'merchandise'|'lottery_sale'|'lottery_payout'|'fuel'|string} props.salesClass
 * @param {string} [props.className]
 */
export function SalesClassBadge({ salesClass, className = '' }) {
  if (!salesClass) return null;

  let colorClass = 'badge-neutral';
  switch (salesClass) {
    case 'lottery_sale':
      colorClass = 'badge-info';
      break;
    case 'lottery_payout':
      colorClass = 'badge-warning';
      break;
    case 'fuel':
      colorClass = 'badge-success';
      break;
    case 'merchandise':
    default:
      colorClass = 'badge-neutral';
  }

  return (
    <span className={`badge ${colorClass} ${className}`}>
      {formatSalesClass(salesClass)}
    </span>
  );
}
