import { firstWeekday, daysInMonth, ymd } from '../lib/dates.js';

/**
 * Presentational MonthCalendar component.
 * @param {object} props
 * @param {number} props.year
 * @param {number} props.month (1..12)
 * @param {Set<string>} props.available - Set of YYYY-MM-DD dates in S3
 * @param {Map<string, import('../api/verifoneApi.js').LoadedDay>} props.loaded - Map of YYYY-MM-DD to LoadedDay
 * @param {string} props.today - YYYY-MM-DD today in store's timezone
 * @param {string} [props.selected] - Currently selected YYYY-MM-DD
 * @param {boolean} [props.s3Checked=false] - Whether S3 has been checked for this month
 * @param {boolean} [props.checkingS3=false] - Whether an S3 check is currently running
 * @param {() => void} [props.onCheckS3] - Callback to check S3 availability
 * @param {(date: string) => void} props.onSelect
 */
export function MonthCalendar({
  year,
  month,
  available = new Set(),
  loaded = new Map(),
  today,
  selected,
  s3Checked = false,
  checkingS3 = false,
  onCheckS3,
  onSelect,
}) {
  const padDays = firstWeekday(year, month);
  const totalDays = daysInMonth(year, month);
  const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  let s3Count = 0;
  let loadedCount = 0;
  let readyCount = 0;

  for (let d = 1; d <= totalDays; d++) {
    const date = ymd(year, month, d);
    const inS3 = available.has(date);
    const isLoaded = loaded.has(date);

    if (inS3) s3Count++;
    if (isLoaded) loadedCount++;
    if (inS3 && !isLoaded && date <= today) readyCount++;
  }

  const days = [];
  for (let d = 1; d <= totalDays; d++) {
    const date = ymd(year, month, d);
    const isFuture = date > today;
    const isLoaded = loaded.has(date);
    const loadedData = loaded.get(date);
    const inS3 = available.has(date);
    const isSelected = date === selected;

    let cellClass = 'cal-day';
    let indicator = null;
    let stateLabel = s3Checked ? 'Not in S3' : 'Not loaded';

    if (isFuture) {
      cellClass += ' cal-day--future';
      stateLabel = 'Future';
    } else if (isLoaded) {
      cellClass += ' cal-day--loaded';
      indicator = `✓ v${loadedData?.versionNo ?? 1}`;
      stateLabel = `Loaded version ${loadedData?.versionNo ?? 1}`;
    } else if (s3Checked) {
      if (inS3) {
        cellClass += ' cal-day--available';
        indicator = '●';
        stateLabel = 'Available in S3';
      } else {
        cellClass += ' cal-day--missing';
        indicator = '–';
        stateLabel = 'Not in S3';
      }
    } else {
      // S3 not checked yet: neutral unloaded state
      stateLabel = 'Not loaded (S3 not checked)';
    }

    if (isSelected) {
      cellClass += ' cal-day--selected';
    }

    days.push({
      dayNumber: d,
      date,
      cellClass,
      indicator,
      stateLabel,
      isFuture,
    });
  }

  return (
    <div className="calendar-wrapper">
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '0.75rem',
          fontSize: '0.8125rem',
          color: 'var(--text-secondary)',
          flexWrap: 'wrap',
          gap: '0.5rem',
        }}
      >
        <span>
          {s3Checked ? (
            <>
              <strong>S3:</strong> {s3Count} days &nbsp;·&nbsp; <strong>Loaded:</strong> {loadedCount} &nbsp;·&nbsp; <strong>Ready to load:</strong> {readyCount}
            </>
          ) : (
            <>
              <strong>Loaded:</strong> {loadedCount} {loadedCount === 1 ? 'day' : 'days'} &nbsp;·&nbsp; <span style={{ color: 'var(--text-muted)' }}>S3: Not checked</span>
            </>
          )}
        </span>

        {onCheckS3 && (
          <button
            type="button"
            onClick={onCheckS3}
            disabled={checkingS3}
            className="btn btn-secondary btn-sm"
            style={{ padding: '0.25rem 0.625rem', fontSize: '0.75rem' }}
          >
            {checkingS3 ? (
              <>
                <span className="spinner" aria-hidden="true" style={{ width: 12, height: 12 }} />
                <span>Checking S3…</span>
              </>
            ) : s3Checked ? (
              '🔄 Re-check S3'
            ) : (
              '☁️ Check S3 for this month'
            )}
          </button>
        )}
      </div>

      <div className="calendar-header-days" aria-hidden="true">
        {weekdays.map((w) => (
          <div key={w}>{w}</div>
        ))}
      </div>

      <div className="calendar-grid" role="grid" aria-label="Month days">
        {Array.from({ length: padDays }).map((_, i) => (
          <div key={`pad-${i}`} className="cal-day cal-day--empty" aria-hidden="true" />
        ))}

        {days.map((item) => (
          <button
            key={item.date}
            type="button"
            className={item.cellClass}
            disabled={item.isFuture}
            onClick={() => onSelect(item.date)}
            aria-label={`${item.date}: ${item.stateLabel}`}
          >
            <span>{item.dayNumber}</span>
            {item.indicator && (
              <span className="cal-day-indicator">{item.indicator}</span>
            )}
          </button>
        ))}
      </div>

      <div className="cal-legend" aria-label="Calendar legend">
        <div className="cal-legend-item">
          <span className="cal-legend-swatch cal-day--loaded" aria-hidden="true">✓</span>
          <span>Loaded</span>
        </div>
        <div className="cal-legend-item">
          <span className="cal-legend-swatch cal-day--available" aria-hidden="true">●</span>
          <span>In S3</span>
        </div>
        <div className="cal-legend-item">
          <span className="cal-legend-swatch cal-day--missing" aria-hidden="true">–</span>
          <span>Not in S3</span>
        </div>
        <div className="cal-legend-item">
          <span className="cal-legend-swatch cal-day--future" aria-hidden="true" />
          <span>Future</span>
        </div>
      </div>
    </div>
  );
}
export default MonthCalendar;
