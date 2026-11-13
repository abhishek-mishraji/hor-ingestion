import { useState } from 'react';
import { Link } from 'react-router';
import { useVendor } from '../vendors/VendorProvider.jsx';
import { useStores } from '../session/StoresProvider.jsx';
import { useAction } from '../hooks/useAction.js';
import { Table } from '../ui/Table.jsx';
import { StatusBadge } from '../ui/StatusBadge.jsx';
import { ErrorBanner, SuccessBanner } from '../ui/ErrorBanner.jsx';
import { formatDateTime } from '../ui/format.js';

/**
 * Stores page: displays synced POS stores and allows running "Sync stores".
 */
export function StoresPage() {
  const { vendor, config, api } = useVendor();
  const { stores, loading, error: loadError, reload } = useStores();
  const [syncResult, setSyncResult] = useState(null);

  const {
    execute: handleSync,
    loading: syncing,
    error: syncError,
    clearError: clearSyncError,
  } = useAction(async () => {
    const result = await api.syncStores();
    setSyncResult(result);
    await reload();
    return result;
  });

  const columns = [
    {
      key: 'storeCode',
      header: 'Code',
      className: 'mono',
      render: (row) => <span className="mono">{row.storeCode}</span>,
    },
    {
      key: 'storeName',
      header: 'Name',
      render: (row) => <strong>{row.storeName}</strong>,
    },
    {
      key: 'timezone',
      header: 'Timezone',
    },
    {
      key: 's3Folder',
      header: 'S3 Folder',
      className: 'mono',
      render: (row) => (
        <span
          className="mono"
          title={row.s3Folder}
          style={{
            display: 'inline-block',
            maxWidth: '180px',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            verticalAlign: 'middle',
            fontSize: '0.8125rem',
          }}
        >
          {row.s3Folder}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'syncedAt',
      header: 'Synced',
      render: (row) => formatDateTime(row.syncedAt),
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (row) => {
        const isActive = row.status === 'ACTIVE';
        return (
          <div style={{ display: 'inline-flex', gap: '0.75rem', alignItems: 'center' }}>
            {isActive && (
              <Link to={`/${vendor}/fetch?storeId=${row.storeId}`} className="btn btn-secondary btn-sm">
                Fetch
              </Link>
            )}
            <Link to={`/${vendor}/history?storeId=${row.storeId}`} className="btn btn-secondary btn-sm">
              History
            </Link>
            <Link to={`/${vendor}/departments?storeId=${row.storeId}`} className="btn btn-secondary btn-sm">
              Departments
            </Link>
          </div>
        );
      },
    },
  ];

  return (
    <div>
      <div className="page-header">
        <div className="page-title-group">
          <h1>Stores</h1>
          <p className="page-description">
            Stores come from hor-auth. A change there (new store, name, S3 folder, deactivation) appears here only after Sync stores.
          </p>
        </div>
        <div className="page-actions">
          <button
            type="button"
            onClick={() => handleSync()}
            disabled={syncing}
            className="btn btn-primary"
          >
            {syncing ? (
              <>
                <span className="spinner" aria-hidden="true" style={{ width: 16, height: 16 }} />
                <span>Syncing stores…</span>
              </>
            ) : (
              'Sync stores'
            )}
          </button>
        </div>
      </div>

      <ErrorBanner error={loadError} title="Failed to load stores" />
      <ErrorBanner error={syncError} title="Store sync failed" onDismiss={clearSyncError} />

      {syncResult && (
        <div className="card" style={{ marginBottom: '1.5rem' }}>
          <SuccessBanner
            message="Store sync completed successfully."
            onDismiss={() => setSyncResult(null)}
          />
          <div className="stat-grid" style={{ marginTop: '1rem' }}>
            <div className="stat-card">
              <div className="stat-label">Inserted</div>
              <div className="stat-value" style={{ color: 'var(--success)' }}>
                {syncResult.inserted}
              </div>
            </div>
            <div className="stat-card">
              <div className="stat-label">Updated</div>
              <div className="stat-value" style={{ color: 'var(--info)' }}>
                {syncResult.updated}
              </div>
            </div>
            <div className="stat-card">
              <div className="stat-label">Deactivated</div>
              <div className="stat-value" style={{ color: 'var(--warning)' }}>
                {syncResult.deactivated}
              </div>
            </div>
            <div className="stat-card">
              <div className="stat-label">Unchanged</div>
              <div className="stat-value" style={{ color: 'var(--text-muted)' }}>
                {syncResult.unchanged}
              </div>
            </div>
          </div>

          {Array.isArray(syncResult.conflicts) && syncResult.conflicts.length > 0 && (
            <div style={{ marginTop: '1.25rem' }}>
              <div className="banner banner-warning">
                <div className="banner-icon" aria-hidden="true">⚠️</div>
                <div className="banner-content">
                  <div className="banner-title">
                    {syncResult.conflicts.length} store(s) could not be synced
                  </div>
                  <table className="data-table" style={{ marginTop: '0.5rem', background: 'var(--bg-surface)' }}>
                    <thead>
                      <tr>
                        <th style={{ padding: '0.5rem 0.75rem' }}>Store ID</th>
                        <th style={{ padding: '0.5rem 0.75rem' }}>Store Code</th>
                        <th style={{ padding: '0.5rem 0.75rem' }}>Reason</th>
                      </tr>
                    </thead>
                    <tbody>
                      {syncResult.conflicts.map((c, idx) => (
                        <tr key={idx}>
                          <td className="mono" style={{ padding: '0.5rem 0.75rem' }}>{c.storeId}</td>
                          <td className="mono" style={{ padding: '0.5rem 0.75rem' }}>{c.storeCode}</td>
                          <td style={{ padding: '0.5rem 0.75rem' }}>{c.reason}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      <Table
        columns={columns}
        data={stores}
        keyField="storeId"
        loading={loading}
        emptyMessage={`No stores yet. Click Sync stores to copy the ${config.label} stores from hor-auth.`}
      />
    </div>
  );
}

export default StoresPage;
