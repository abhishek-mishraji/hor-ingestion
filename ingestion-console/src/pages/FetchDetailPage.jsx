import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, Link } from 'react-router';
import { useVendor } from '../vendors/VendorProvider.jsx';
import { useStores } from '../session/StoresProvider.jsx';
import { usePolling } from '../hooks/usePolling.js';
import { isOpen } from '../api/ingestionApi.js';
import { StatusBadge, ModeBadge } from '../ui/StatusBadge.jsx';
import { ErrorBanner } from '../ui/ErrorBanner.jsx';
import {
  formatDateTime,
  formatDuration,
  formatBytes,
  formatNumber,
} from '../ui/format.js';

function formatCheckValue(val) {
  if (val == null || val === '') return '—';
  if (typeof val === 'number') {
    if (Number.isInteger(val)) return formatNumber(val);
    return val.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  return String(val);
}

/**
 * FetchDetailPage: Live progress and complete report for an ingestion fetch job.
 */
export function FetchDetailPage() {
  const { id } = useParams();
  const { vendor, api } = useVendor();
  const { byId } = useStores();

  const fetchLoader = useCallback(() => api.getFetch(id), [api, id]);
  const shouldContinue = useCallback(
    (data) => isOpen(data?.fetch?.status),
    []
  );

  const { data: detail, loading, error, reload } = usePolling(
    fetchLoader,
    2000,
    shouldContinue,
    [id, api]
  );

  // Client-side live elapsed timer while status is open (CLAIMED or RUNNING)
  const [now, setNow] = useState(Date.now());
  const isJobOpen = isOpen(detail?.fetch?.status);

  useEffect(() => {
    if (!isJobOpen) return;
    const interval = setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => clearInterval(interval);
  }, [isJobOpen]);

  // Copy zip key state
  const [copiedKey, setCopiedKey] = useState(false);
  const handleCopyKey = (key) => {
    if (!key) return;
    navigator.clipboard.writeText(key).then(() => {
      setCopiedKey(true);
      setTimeout(() => setCopiedKey(false), 2000);
    });
  };

  const fetchRecord = detail?.fetch;
  const zip = detail?.zip;
  const sections = detail?.sections || [];

  const store = fetchRecord ? byId.get(fetchRecord.storeId) : null;
  const storeLabel = store
    ? `${store.storeCode}: ${store.storeName}`
    : fetchRecord
    ? `Store #${fetchRecord.storeId}`
    : '—';

  // Calculate live elapsed duration
  const elapsedMs = useMemo(() => {
    if (!fetchRecord) return null;
    if (fetchRecord.durationMs != null) return fetchRecord.durationMs;
    if (fetchRecord.requestedAt) {
      const start = new Date(fetchRecord.requestedAt).getTime();
      return Math.max(0, now - start);
    }
    return null;
  }, [fetchRecord, now]);

  if (loading && !detail) {
    return (
      <div className="loading-container">
        <span className="spinner" aria-hidden="true" />
        <span>Loading fetch #{id}…</span>
      </div>
    );
  }

  if (error) {
    const is404 = error.status === 404;
    return (
      <div>
        <div className="page-header">
          <div className="page-title-group">
            <h1>Fetch #{id}</h1>
          </div>
          <div className="page-actions">
            <Link to={`/${vendor}/history`} className="btn btn-secondary">
              Back to History
            </Link>
          </div>
        </div>
        <div className="card">
          <ErrorBanner
            error={is404 ? `Fetch #${id} not found.` : error}
            title={is404 ? 'Not Found' : 'Failed to load fetch details'}
          />
          {!is404 && (
            <button
              type="button"
              onClick={() => reload()}
              className="btn btn-secondary btn-sm"
              style={{ marginTop: '0.75rem' }}
            >
              Retry
            </button>
          )}
        </div>
      </div>
    );
  }

  if (!fetchRecord) return null;

  const monthParam = fetchRecord.businessDate ? fetchRecord.businessDate.slice(0, 7) : '';

  let messageCalloutClass = 'callout';
  if (fetchRecord.status === 'FAILED' || fetchRecord.status === 'NOT_FOUND') {
    messageCalloutClass = 'callout callout-danger';
  } else if (fetchRecord.status === 'REFUSED') {
    messageCalloutClass = 'callout callout-warning';
  } else if (fetchRecord.status === 'LOADED') {
    messageCalloutClass = 'callout callout-info';
  }

  return (
    <div>
      <div className="page-header">
        <div className="page-title-group">
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
            <h1>Fetch #{fetchRecord.id}</h1>
            <StatusBadge status={fetchRecord.status} live={isJobOpen} />
            <ModeBadge mode={fetchRecord.mode} />
            {isJobOpen && (
              <span className="badge badge-info badge-live" role="status">
                Live
              </span>
            )}
          </div>
          <p className="page-description">
            Ingestion job for {storeLabel} on business date {fetchRecord.businessDate}.
          </p>
        </div>

        <div className="page-actions">
          <Link
            to={`/${vendor}/fetch?storeId=${fetchRecord.storeId}&month=${monthParam}`}
            className="btn btn-secondary"
          >
            Back to calendar
          </Link>
          <Link
            to={`/${vendor}/history?storeId=${fetchRecord.storeId}`}
            className="btn btn-secondary"
          >
            History for this store
          </Link>
        </div>
      </div>

      {fetchRecord.message && (
        <div className={messageCalloutClass} style={{ whiteSpace: 'pre-wrap', lineHeight: 1.5 }}>
          <strong>Status message:</strong>
          <div style={{ marginTop: '0.25rem' }}>{fetchRecord.message}</div>
        </div>
      )}

      {/* Primary Details Card */}
      <div className="card">
        <h2 className="card-title" style={{ marginBottom: '1.25rem' }}>
          Job Overview
        </h2>
        <div className="detail-grid">
          <div className="detail-item">
            <span className="detail-label">Store</span>
            <span className="detail-value">{storeLabel}</span>
          </div>

          <div className="detail-item">
            <span className="detail-label">Business Date</span>
            <span className="detail-value mono">{fetchRecord.businessDate}</span>
          </div>

          {fetchRecord.fileType && (
            <div className="detail-item">
              <span className="detail-label">File Type</span>
              <span className="detail-value mono">{fetchRecord.fileType}</span>
            </div>
          )}

          <div className="detail-item">
            <span className="detail-label">Mode</span>
            <span className="detail-value">
              <ModeBadge mode={fetchRecord.mode} />
            </span>
          </div>

          <div className="detail-item">
            <span className="detail-label">Status</span>
            <span className="detail-value">
              <StatusBadge status={fetchRecord.status} live={isJobOpen} />
            </span>
          </div>

          <div className="detail-item">
            <span className="detail-label">Requested By</span>
            <span className="detail-value mono">#{fetchRecord.requestedBy}</span>
          </div>

          <div className="detail-item">
            <span className="detail-label">Requested At</span>
            <span className="detail-value">{formatDateTime(fetchRecord.requestedAt)}</span>
          </div>

          <div className="detail-item">
            <span className="detail-label">Finished At</span>
            <span className="detail-value">{formatDateTime(fetchRecord.finishedAt)}</span>
          </div>

          <div className="detail-item">
            <span className="detail-label">Duration</span>
            <span className="detail-value">
              {isJobOpen ? (
                <span style={{ color: 'var(--primary)', fontWeight: 600 }}>
                  {formatDuration(elapsedMs)} (running…)
                </span>
              ) : (
                formatDuration(fetchRecord.durationMs)
              )}
            </span>
          </div>
        </div>
      </div>

      {/* S3 Zip Archive Details */}
      {zip && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">S3 Zip Archive</h2>
            <span className="mono" style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
              v{zip.version}
            </span>
          </div>

          <div className="detail-grid">
            <div className="detail-item" style={{ gridColumn: '1 / -1' }}>
              <span className="detail-label">Key</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
                <span className="detail-value mono" style={{ wordBreak: 'break-all' }}>
                  {zip.key}
                </span>
                <button
                  type="button"
                  onClick={() => handleCopyKey(zip.key)}
                  className="btn btn-secondary btn-sm"
                  aria-label="Copy S3 key to clipboard"
                >
                  {copiedKey ? '✓ Copied' : 'Copy key'}
                </button>
              </div>
            </div>

            <div className="detail-item">
              <span className="detail-label">Bucket</span>
              <span className="detail-value mono">{zip.bucket}</span>
            </div>

            {zip.fileType && (
              <div className="detail-item">
                <span className="detail-label">File Type</span>
                <span className="detail-value mono">{zip.fileType}</span>
              </div>
            )}

            <div className="detail-item">
              <span className="detail-label">Version</span>
              <span className="detail-value">{zip.version}</span>
            </div>

            {zip.files != null && (
              <div className="detail-item">
                <span className="detail-label">Files</span>
                <span className="detail-value">{formatNumber(zip.files)}</span>
              </div>
            )}

            <div className="detail-item">
              <span className="detail-label">Size</span>
              <span className="detail-value">{formatBytes(zip.sizeBytes)}</span>
            </div>

            <div className="detail-item">
              <span className="detail-label">ETag</span>
              <span className="detail-value mono" style={{ fontSize: '0.8125rem' }}>
                {zip.etag}
              </span>
            </div>

            <div className="detail-item">
              <span className="detail-label">Last Modified</span>
              <span className="detail-value">{formatDateTime(zip.lastModified)}</span>
            </div>

            <div className="detail-item">
              <span className="detail-label">Manifest Created</span>
              <span className="detail-value">{formatDateTime(zip.manifestCreatedAt)}</span>
            </div>

            <div className="detail-item">
              <span className="detail-label">Mirror Version</span>
              <span className="detail-value mono">{zip.mirrorVersion || '—'}</span>
            </div>
          </div>
        </div>
      )}

      {/* Generic Loaded Sections (Files for Verifone, Day Package for Gilbarco) */}
      {sections.map((section, secIdx) => {
        const rowsEntries = Object.entries(section.rows || {}).sort(([a], [b]) => a.localeCompare(b));
        const totalRows = rowsEntries.reduce((sum, [, count]) => sum + (count || 0), 0);

        const checks = Array.isArray(section.checks) ? [...section.checks] : [];
        // Sort failed checks first, then by name
        checks.sort((a, b) => {
          if (a.passed === b.passed) return (a.name || '').localeCompare(b.name || '');
          return a.passed ? 1 : -1;
        });

        const passedChecksCount = checks.filter((c) => c.passed).length;

        return (
          <div key={section.title || secIdx} className="card">
            <div className="card-header">
              <div>
                <h2 className="card-title" style={{ fontSize: '1.125rem' }}>
                  {section.title}
                </h2>
                {section.daySeq != null && (
                  <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '0.25rem' }}>
                    Day Seq: <strong>{section.daySeq}</strong>
                    {section.loadedAt ? ` · Loaded at: ${formatDateTime(section.loadedAt)}` : ''}
                  </p>
                )}
              </div>
            </div>

            {/* Ingested Rows Table */}
            <div style={{ marginBottom: '1.5rem' }}>
              <h3 style={{ fontSize: '0.9375rem', fontWeight: 600, marginBottom: '0.75rem', color: 'var(--text-secondary)' }}>
                Ingested Table Rows
              </h3>
              <div className="table-wrapper">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th scope="col">Table</th>
                      <th scope="col" className="num">Rows</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rowsEntries.length === 0 ? (
                      <tr>
                        <td colSpan={2} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                          No rows ingested
                        </td>
                      </tr>
                    ) : (
                      <>
                        {rowsEntries.map(([tbl, count]) => (
                          <tr key={tbl}>
                            <td className="mono">{tbl}</td>
                            <td className="num">{formatNumber(count)}</td>
                          </tr>
                        ))}
                        <tr style={{ fontWeight: 700, backgroundColor: 'var(--bg-surface-raised)' }}>
                          <td>Total</td>
                          <td className="num">{formatNumber(totalRows)}</td>
                        </tr>
                      </>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Load Checks Table */}
            <div>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: '0.75rem',
                }}
              >
                <h3 style={{ fontSize: '0.9375rem', fontWeight: 600, color: 'var(--text-secondary)' }}>
                  Load Checks ({passedChecksCount} of {checks.length} passed)
                </h3>
              </div>

              <div className="table-wrapper">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th scope="col">Check Name</th>
                      <th scope="col" className="num">Expected</th>
                      <th scope="col" className="num">Actual</th>
                      <th scope="col">Hard</th>
                      <th scope="col">Result</th>
                      <th scope="col">Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    {checks.length === 0 ? (
                      <tr>
                        <td colSpan={6} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                          No checks recorded
                        </td>
                      </tr>
                    ) : (
                      checks.map((chk, cIdx) => (
                        <tr key={chk.name || cIdx}>
                          <td>{chk.name}</td>
                          <td className="num">{formatCheckValue(chk.expected)}</td>
                          <td className="num">{formatCheckValue(chk.actual)}</td>
                          <td>{chk.hard ? 'Yes' : 'No'}</td>
                          <td>
                            {chk.passed ? (
                              <span style={{ color: 'var(--success)', fontWeight: 600 }}>
                                ✓ Pass
                              </span>
                            ) : (
                              <span style={{ color: 'var(--danger)', fontWeight: 600 }}>
                                ✗ Fail
                              </span>
                            )}
                          </td>
                          <td style={{ fontSize: '0.8125rem', color: chk.passed ? 'var(--text-muted)' : 'var(--danger)' }}>
                            {chk.detail || '—'}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

export default FetchDetailPage;
