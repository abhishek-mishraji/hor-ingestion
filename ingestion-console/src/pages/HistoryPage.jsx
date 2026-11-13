import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useSearchParams, Link } from 'react-router';
import { useVendor } from '../vendors/VendorProvider.jsx';
import { useStores } from '../session/StoresProvider.jsx';
import { isOpen } from '../api/ingestionApi.js';
import { StorePicker } from '../ui/StorePicker.jsx';
import { Field } from '../ui/Field.jsx';
import { StatusBadge, ModeBadge } from '../ui/StatusBadge.jsx';
import { Pager } from '../ui/Pager.jsx';
import { ErrorBanner } from '../ui/ErrorBanner.jsx';
import { formatDateTime, formatDuration } from '../ui/format.js';

/**
 * HistoryPage: Browse, filter, and inspect POS ingestion history.
 */
export function HistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { vendor, config, api } = useVendor();
  const { stores, byId } = useStores();

  const storeIdParam = searchParams.get('storeId') || '';
  const fromParam = searchParams.get('from') || '';
  const toParam = searchParams.get('to') || '';
  const fileTypeParam = searchParams.get('fileType') || '';
  const offsetParam = Number(searchParams.get('offset')) || 0;
  const limitParam = Number(searchParams.get('limit')) || 50;

  // Local filter inputs for Apply/Reset workflow
  const [filterStoreId, setFilterStoreId] = useState(storeIdParam);
  const [filterFrom, setFilterFrom] = useState(fromParam);
  const [filterTo, setFilterTo] = useState(toParam);
  const [filterFileType, setFilterFileType] = useState(fileTypeParam);

  // Load available file types for vendor if applicable
  const [fileTypes, setFileTypes] = useState(/** @type {string[]} */ ([]));
  useEffect(() => {
    if (!config.hasFileTypes) return;
    let active = true;
    api.listFileTypes()
      .then((types) => {
        if (!active) return;
        setFileTypes(Array.isArray(types) ? types : []);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [config.hasFileTypes, api]);

  // Sync filter inputs when URL searchParams change
  useEffect(() => {
    setFilterStoreId(storeIdParam);
    setFilterFrom(fromParam);
    setFilterTo(toParam);
    setFilterFileType(fileTypeParam);
  }, [storeIdParam, fromParam, toParam, fileTypeParam]);

  // Expanded row IDs
  const [expandedIds, setExpandedIds] = useState(new Set());
  const toggleExpand = (id) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  // History data state
  const [items, setItems] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const pollTimerRef = useRef(null);

  const fetchHistory = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {
        limit: limitParam,
        offset: offsetParam,
      };
      if (storeIdParam) params.storeId = storeIdParam;
      if (fromParam) params.from = fromParam;
      if (toParam) params.to = toParam;
      if (fileTypeParam) params.fileType = fileTypeParam;

      const page = await api.listFetches(params);
      setItems(page.items || []);
      setTotal(page.total || 0);
    } catch (err) {
      setError(err);
      setItems([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [api, storeIdParam, fromParam, toParam, fileTypeParam, limitParam, offsetParam]);

  useEffect(() => {
    fetchHistory();
  }, [fetchHistory]);

  // Polling every 5s if any item is CLAIMED or RUNNING
  const hasOpenJobs = useMemo(() => items.some((it) => isOpen(it.status)), [items]);

  useEffect(() => {
    if (!hasOpenJobs) return;

    pollTimerRef.current = setTimeout(async () => {
      try {
        const params = {
          limit: limitParam,
          offset: offsetParam,
        };
        if (storeIdParam) params.storeId = storeIdParam;
        if (fromParam) params.from = fromParam;
        if (toParam) params.to = toParam;
        if (fileTypeParam) params.fileType = fileTypeParam;

        const page = await api.listFetches(params);
        setItems(page.items || []);
        setTotal(page.total || 0);
      } catch {
        // Suppress background poll errors
      }
    }, 5000);

    return () => {
      if (pollTimerRef.current) clearTimeout(pollTimerRef.current);
    };
  }, [hasOpenJobs, api, storeIdParam, fromParam, toParam, fileTypeParam, limitParam, offsetParam]);

  const handleApply = (e) => {
    e.preventDefault();
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (filterStoreId) next.set('storeId', filterStoreId);
        else next.delete('storeId');

        if (filterFrom) next.set('from', filterFrom);
        else next.delete('from');

        if (filterTo) next.set('to', filterTo);
        else next.delete('to');

        if (filterFileType) next.set('fileType', filterFileType);
        else next.delete('fileType');

        next.set('offset', '0');
        return next;
      },
      { replace: true }
    );
  };

  const handleReset = () => {
    setFilterStoreId('');
    setFilterFrom('');
    setFilterTo('');
    setFilterFileType('');
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams();
        if (prev.get('limit')) next.set('limit', prev.get('limit'));
        return next;
      },
      { replace: true }
    );
  };

  const handleOffsetChange = (newOffset) => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set('offset', String(newOffset));
        return next;
      },
      { replace: true }
    );
  };

  const handleLimitChange = (newLimit) => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set('limit', String(newLimit));
        next.set('offset', '0');
        return next;
      },
      { replace: true }
    );
  };

  const colSpanCount = config.hasFileTypes ? 10 : 9;

  const fileTypeOptions = useMemo(() => {
    const list = [{ value: '', label: 'All file types' }];
    for (const ft of fileTypes) {
      list.push({ value: ft, label: ft });
    }
    return list;
  }, [fileTypes]);

  return (
    <div>
      <div className="page-header">
        <div className="page-title-group">
          <h1>History</h1>
          <p className="page-description">
            Audit log and execution history of {config.label} ingestion jobs.
          </p>
        </div>
      </div>

      {/* Filter Toolbar */}
      <form onSubmit={handleApply} className="filter-bar">
        <div style={{ minWidth: '220px', flex: '1 1 220px' }}>
          <StorePicker
            stores={stores}
            value={filterStoreId}
            onChange={setFilterStoreId}
            includeAll
            label="Filter Store"
          />
        </div>

        {config.hasFileTypes && (
          <div style={{ minWidth: '150px' }}>
            <Field
              as="select"
              label="File Type"
              name="fileType"
              value={filterFileType}
              onChange={(e) => setFilterFileType(e.target.value)}
              options={fileTypeOptions}
            />
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.375rem', marginBottom: '1rem' }}>
          <label htmlFor="history-from" className="field-label">From Date</label>
          <input
            id="history-from"
            type="date"
            value={filterFrom}
            onChange={(e) => setFilterFrom(e.target.value)}
            className="field-input"
          />
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.375rem', marginBottom: '1rem' }}>
          <label htmlFor="history-to" className="field-label">To Date</label>
          <input
            id="history-to"
            type="date"
            value={filterTo}
            onChange={(e) => setFilterTo(e.target.value)}
            className="field-input"
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '1rem', alignSelf: 'flex-end' }}>
          <button type="submit" className="btn btn-primary">
            Apply
          </button>
          <button
            type="button"
            onClick={handleReset}
            className="btn btn-secondary"
          >
            Reset
          </button>
        </div>
      </form>

      <ErrorBanner error={error} title="Failed to load history" />

      {/* Data Table */}
      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th scope="col" style={{ width: '40px' }} aria-label="Expand row" />
              <th scope="col">ID</th>
              <th scope="col">Store</th>
              {config.hasFileTypes && <th scope="col">File Type</th>}
              <th scope="col">Business Date</th>
              <th scope="col">Mode</th>
              <th scope="col">Status</th>
              <th scope="col">Requested At</th>
              <th scope="col">Duration</th>
              <th scope="col">Message</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={colSpanCount} style={{ textAlign: 'center', padding: '2.5rem' }}>
                  <div style={{ display: 'inline-flex', alignItems: 'center', gap: '0.75rem' }}>
                    <span className="spinner" aria-hidden="true" />
                    <span>Loading history…</span>
                  </div>
                </td>
              </tr>
            ) : items.length === 0 ? (
              <tr>
                <td colSpan={colSpanCount} style={{ textAlign: 'center', padding: '2.5rem' }}>
                  <div className="empty-state">
                    <div className="empty-state-icon" aria-hidden="true">📭</div>
                    <div className="empty-state-text">No fetches match these filters.</div>
                  </div>
                </td>
              </tr>
            ) : (
              items.map((row) => {
                const store = byId.get(row.storeId);
                const storeCode = store?.storeCode || `#${row.storeId}`;
                const isExpanded = expandedIds.has(row.id);
                const rowIsOpen = isOpen(row.status);

                return (
                  <tr key={row.id} style={{ verticalAlign: 'middle' }}>
                    <td colSpan={colSpanCount} style={{ padding: 0 }}>
                      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <tbody>
                          <tr
                            onClick={() => toggleExpand(row.id)}
                            style={{ cursor: 'pointer' }}
                          >
                            <td style={{ width: '40px', textAlign: 'center', padding: '0.875rem 0.5rem' }}>
                              <button
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  toggleExpand(row.id);
                                }}
                                aria-expanded={isExpanded}
                                aria-label={`${isExpanded ? 'Collapse' : 'Expand'} fetch #${row.id}`}
                                style={{
                                  background: 'none',
                                  border: 'none',
                                  cursor: 'pointer',
                                  fontSize: '0.875rem',
                                  color: 'var(--text-muted)',
                                  padding: '0.25rem',
                                }}
                              >
                                {isExpanded ? '▼' : '▶'}
                              </button>
                            </td>
                            <td style={{ width: '80px' }}>
                              <Link
                                to={`/${vendor}/fetches/${row.id}`}
                                onClick={(e) => e.stopPropagation()}
                                className="mono"
                                style={{ fontWeight: 600 }}
                              >
                                #{row.id}
                              </Link>
                            </td>
                            <td className="mono" style={{ width: '120px' }}>{storeCode}</td>
                            {config.hasFileTypes && (
                              <td className="mono" style={{ width: '90px' }}>{row.fileType || '—'}</td>
                            )}
                            <td className="mono" style={{ width: '120px' }}>{row.businessDate}</td>
                            <td style={{ width: '100px' }}>
                              <ModeBadge mode={row.mode} />
                            </td>
                            <td style={{ width: '130px' }}>
                              <StatusBadge status={row.status} live={rowIsOpen} />
                            </td>
                            <td style={{ width: '180px' }}>{formatDateTime(row.requestedAt)}</td>
                            <td style={{ width: '110px' }}>
                              {rowIsOpen ? (
                                <span style={{ color: 'var(--primary)', fontWeight: 600 }}>
                                  running…
                                </span>
                              ) : (
                                formatDuration(row.durationMs)
                              )}
                            </td>
                            <td>
                              <div
                                style={{
                                  maxWidth: '260px',
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis',
                                  whiteSpace: 'nowrap',
                                  color: 'var(--text-secondary)',
                                  fontSize: '0.8125rem',
                                }}
                                title={row.message || ''}
                              >
                                {row.message || '—'}
                              </div>
                            </td>
                          </tr>

                          {isExpanded && (
                            <tr className="row-expand">
                              <td colSpan={colSpanCount} style={{ padding: '1rem 1.5rem', backgroundColor: 'var(--bg-surface-raised)', borderBottom: '1px solid var(--border-subtle)' }}>
                                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                    <strong style={{ fontSize: '0.875rem', color: 'var(--text-main)' }}>
                                      Fetch #{row.id} Details:
                                    </strong>
                                    <Link to={`/${vendor}/fetches/${row.id}`} className="btn btn-secondary btn-sm">
                                      Open details ↗
                                    </Link>
                                  </div>
                                  <div style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', whiteSpace: 'pre-wrap', lineHeight: 1.5 }}>
                                    {row.message ? row.message : 'No message recorded for this fetch.'}
                                  </div>
                                </div>
                              </td>
                            </tr>
                          )}
                        </tbody>
                      </table>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      <Pager
        total={total}
        offset={offsetParam}
        limit={limitParam}
        onOffsetChange={handleOffsetChange}
        onLimitChange={handleLimitChange}
        limitOptions={[25, 50, 100, 200]}
      />
    </div>
  );
}

export default HistoryPage;
