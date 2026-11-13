/**
 * @typedef {object} Column
 * @property {string} key - Unique key for column
 * @property {React.ReactNode} header - Header label
 * @property {(row: any, index: number) => React.ReactNode} [render] - Custom cell renderer
 * @property {string} [className] - Optional class
 * @property {string} [width] - CSS width
 */

/**
 * Accessible table component with headers and empty state.
 * @param {object} props
 * @param {Array<Column>} props.columns - Column configuration
 * @param {Array<any>} props.data - Data rows
 * @param {string|((row: any) => string|number)} [props.keyField='id'] - Key extractor
 * @param {boolean} [props.loading=false] - Loading indicator
 * @param {string} [props.emptyMessage='No records found.']
 * @param {string} [props.className]
 */
export function Table({
  columns,
  data = [],
  keyField = 'id',
  loading = false,
  emptyMessage = 'No records found.',
  className = '',
}) {
  const getRowKey = (row, index) => {
    if (typeof keyField === 'function') return keyField(row);
    return row[keyField] ?? index;
  };

  return (
    <div className={`table-wrapper ${className}`}>
      <table className="data-table">
        <thead>
          <tr>
            {columns.map((col) => (
              <th
                key={col.key}
                style={col.width ? { width: col.width } : undefined}
                className={col.className || ''}
                scope="col"
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <tr>
              <td colSpan={columns.length} style={{ textAlign: 'center', padding: '2.5rem' }}>
                <div style={{ display: 'inline-flex', alignItems: 'center', gap: '0.75rem' }}>
                  <span className="spinner" aria-hidden="true" />
                  <span>Loading data...</span>
                </div>
              </td>
            </tr>
          ) : data.length === 0 ? (
            <tr>
              <td colSpan={columns.length} style={{ textAlign: 'center', padding: '2.5rem' }}>
                <div className="empty-state">
                  <div className="empty-state-icon" aria-hidden="true">📭</div>
                  <div className="empty-state-text">{emptyMessage}</div>
                </div>
              </td>
            </tr>
          ) : (
            data.map((row, idx) => (
              <tr key={getRowKey(row, idx)}>
                {columns.map((col) => (
                  <td key={col.key} className={col.className || ''}>
                    {col.render ? col.render(row, idx) : (row[col.key] ?? '—')}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
