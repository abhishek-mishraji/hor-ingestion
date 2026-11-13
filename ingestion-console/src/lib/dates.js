/**
 * @file dates.js
 * Pure YYYY-MM-DD date helpers.
 * Never uses `new Date("YYYY-MM-DD")` which parses as UTC and shifts local days.
 */

const pad = (n) => String(n).padStart(2, '0');

export const ymd = (y, m, d) => `${y}-${pad(m)}-${pad(d)}`;

/** Number of days in month (m is 1..12). Date.UTC(y, m, 0) gives the last day of month m. */
export const daysInMonth = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();

/** 0 = Sunday, 1 = Monday, etc. for the 1st of the month. */
export const firstWeekday = (y, m) => new Date(Date.UTC(y, m - 1, 1)).getUTCDay();

export const monthRange = (y, m) => ({ from: ymd(y, m, 1), to: ymd(y, m, daysInMonth(y, m)) });

export const addMonths = (y, m, delta) => {
  const i = y * 12 + (m - 1) + delta;
  return { y: Math.floor(i / 12), m: (i % 12) + 1 };
};

export const parseMonth = (s) => {
  const r = /^(\d{4})-(\d{2})$/.exec(s || '');
  return r ? { y: +r[1], m: +r[2] } : null;
};

export const toMonthParam = (y, m) => `${y}-${pad(m)}`;

/** Today's date in an IANA timezone, e.g. todayIn('America/Indiana/Indianapolis') → "2026-10-22". */
export const todayIn = (tz) =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());

/** "2026-09-08" → "Tuesday, 8 September 2026" (built in UTC so the day never shifts). */
export const longDate = (s) => {
  const [y, m, d] = s.split('-').map(Number);
  return new Intl.DateTimeFormat('en-US', {
    timeZone: 'UTC',
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(Date.UTC(y, m - 1, d)));
};

export const monthLabel = (y, m) =>
  new Intl.DateTimeFormat('en-US', {
    timeZone: 'UTC',
    month: 'long',
    year: 'numeric',
  }).format(new Date(Date.UTC(y, m - 1, 1)));
