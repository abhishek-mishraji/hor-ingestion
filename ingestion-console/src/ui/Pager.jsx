/**
 * Pager component for API offset-limit pagination.
 * @param {object} props
 * @param {number} [props.total=0] - Total number of items
 * @param {number} [props.offset=0] - Current 0-based offset
 * @param {number} [props.limit=50] - Items per page
 * @param {(newOffset: number) => void} props.onOffsetChange - Offset change handler
 * @param {(newLimit: number) => void} [props.onLimitChange] - Limit change handler
 * @param {Array<number>} [props.limitOptions=[10, 25, 50, 100]]
 */
export function Pager({
  total = 0,
  offset = 0,
  limit = 50,
  onOffsetChange,
  onLimitChange,
  limitOptions = [10, 25, 50, 100],
}) {
  const from = total === 0 ? 0 : offset + 1;
  const to = Math.min(offset + limit, total);
  const hasPrev = offset > 0;
  const hasNext = offset + limit < total;

  const handlePrev = () => {
    const newOffset = Math.max(0, offset - limit);
    onOffsetChange(newOffset);
  };

  const handleNext = () => {
    const newOffset = offset + limit;
    if (newOffset < total) {
      onOffsetChange(newOffset);
    }
  };

  return (
    <div className="pager-container" aria-label="Pagination Navigation">
      <div className="pager-info">
        {total === 0 ? (
          '0 entries'
        ) : (
          <>
            Showing <strong>{from}</strong> to <strong>{to}</strong> of <strong>{total}</strong> entries
          </>
        )}
      </div>

      <div className="pager-controls">
        {onLimitChange && (
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.375rem', marginRight: '0.75rem' }}>
            <span style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>Show:</span>
            <select
              value={limit}
              onChange={(e) => onLimitChange(Number(e.target.value))}
              className="filter-select"
              aria-label="Items per page"
              style={{ padding: '0.25rem 0.5rem' }}
            >
              {limitOptions.map((opt) => (
                <option key={opt} value={opt}>
                  {opt}
                </option>
              ))}
            </select>
          </label>
        )}

        <button
          type="button"
          onClick={handlePrev}
          disabled={!hasPrev}
          className="btn btn-secondary btn-sm"
          aria-label="Previous page"
        >
          ← Previous
        </button>
        <button
          type="button"
          onClick={handleNext}
          disabled={!hasNext}
          className="btn btn-secondary btn-sm"
          aria-label="Next page"
        >
          Next →
        </button>
      </div>
    </div>
  );
}
