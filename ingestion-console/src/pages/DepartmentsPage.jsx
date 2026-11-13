import { useState, useEffect, useMemo, useCallback } from 'react';
import { useSearchParams } from 'react-router';
import { useVendor } from '../vendors/VendorProvider.jsx';
import { useStores } from '../session/StoresProvider.jsx';
import { StorePicker } from '../ui/StorePicker.jsx';
import { SalesClassBadge } from '../ui/StatusBadge.jsx';
import { ErrorBanner } from '../ui/ErrorBanner.jsx';
import { DepartmentEditModal, adminLabel } from '../ui/DepartmentEditModal.jsx';
import { useSession } from '../session/SessionProvider.jsx';
import { formatDate, formatDateTime } from '../ui/format.js';

/**
 * A department with sales but no name: the POS files never named it (only scanned, e.g. scratch-off tickets),
 * so it counts as merchandise until an admin names it.
 * @param {import('../api/ingestionApi.js').NormalizedDepartment} d
 */
function needsName(d) {
  return !d.name && Boolean(d.lastSold);
}

/**
 * DepartmentsPage: Browse, filter, and inspect departments and sales classifications for a store.
 */
export function DepartmentsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { config, api } = useVendor();
  const { stores, byId, loading: storesLoading } = useStores();
  const { me } = useSession();
  const canEdit = config.canEditDepartments;

  const storeIdParam = searchParams.get('storeId') || '';

  // Local client-side search text filter (number or name)
  const [searchText, setSearchText] = useState('');
  const [onlyNeedsName, setOnlyNeedsName] = useState(false);

  // Department being edited (admin name / sales class)
  const [editing, setEditing] = useState(null);

  // Department data state
  const [departments, setDepartments] = useState(/** @type {import('../api/ingestionApi.js').NormalizedDepartment[]} */ ([]));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Expanded department IDs for history
  const [expandedDeptNums, setExpandedDeptNums] = useState(new Set());
  const toggleExpand = (deptNum) => {
    setExpandedDeptNums((prev) => {
      const next = new Set(prev);
      if (next.has(deptNum)) next.delete(deptNum);
      else next.add(deptNum);
      return next;
    });
  };

  const fetchDepartments = useCallback(async (storeId) => {
    if (!storeId) {
      setDepartments([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await api.listDepartments(storeId);
      setDepartments(Array.isArray(data) ? data : []);
    } catch (err) {
      setError(err);
      setDepartments([]);
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    setExpandedDeptNums(new Set());
    setEditing(null);
    if (storeIdParam) {
      fetchDepartments(storeIdParam);
    } else {
      setDepartments([]);
    }
  }, [storeIdParam, fetchDepartments]);

  const handleStoreChange = (newStoreId) => {
    setSearchText('');
    setOnlyNeedsName(false);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (newStoreId) {
          next.set('storeId', newStoreId);
        } else {
          next.delete('storeId');
        }
        return next;
      },
      { replace: true }
    );
  };

  const filteredDepartments = useMemo(() => {
    const base = onlyNeedsName ? departments.filter(needsName) : departments;
    if (!searchText.trim()) return base;
    const query = searchText.trim().toLowerCase();
    return base.filter((d) => {
      const matchesNum = String(d.deptNumber).includes(query);
      const matchesName = d.name ? d.name.toLowerCase().includes(query) : false;
      return matchesNum || matchesName;
    });
  }, [departments, searchText, onlyNeedsName]);

  const needsNameCount = useMemo(() => (canEdit ? departments.filter(needsName).length : 0), [departments, canEdit]);

  const handleSaved = (updated) => {
    setDepartments((prev) => prev.map((d) => (d.deptNumber === updated.deptNumber ? { ...d, ...updated } : d)));
    setEditing(null);
  };

  const currentStore = storeIdParam ? byId.get(Number(storeIdParam)) : null;
  const colSpanCount = (config.hasDeptHistory ? 5 : 3) + (canEdit ? 3 : 0);

  return (
    <div>
      <div className="page-header">
        <div className="page-title-group">
          <h1>Departments</h1>
          <p className="page-description">
            {canEdit
              ? 'The sales class follows the department name from the POS files. A platform admin can set the name and class; they then stay, whatever the files say.'
              : 'The sales class is set from the department name by the service. It cannot be edited here.'}
          </p>
        </div>
      </div>

      {/* Top Filter Bar */}
      <div className="card" style={{ padding: '1rem 1.25rem', marginBottom: '1.5rem' }}>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'flex-end',
            justifyContent: 'space-between',
            gap: '1rem',
          }}
        >
          <div style={{ minWidth: '260px', flex: '1 1 300px' }}>
            <StorePicker
              label="Store"
              stores={stores}
              value={storeIdParam}
              onChange={handleStoreChange}
              disabled={storesLoading}
            />
          </div>

          {storeIdParam && (
            <div style={{ minWidth: '220px', flex: '1 1 240px' }}>
              <label htmlFor="dept-search" className="field-label">Filter Department</label>
              <input
                id="dept-search"
                type="text"
                placeholder="Search number or name…"
                value={searchText}
                onChange={(e) => setSearchText(e.target.value)}
                className="field-input"
              />
            </div>
          )}
        </div>
      </div>

      <ErrorBanner error={error} title="Failed to load departments" />

      {canEdit && storeIdParam && needsNameCount > 0 && (
        <div className="callout callout-warning" style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
          <span style={{ flex: '1 1 320px' }}>
            {needsNameCount} {needsNameCount === 1 ? 'department has' : 'departments have'} sales but no name. The POS
            files only scan them (for example scratch-off tickets), so they count as merchandise until you name them.
          </span>
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', cursor: 'pointer' }}>
            <input type="checkbox" checked={onlyNeedsName} onChange={(e) => setOnlyNeedsName(e.target.checked)} />
            Show only these
          </label>
        </div>
      )}

      {!storeIdParam && (
        <div className="empty-state">
          <div className="empty-state-icon" aria-hidden="true">🏪</div>
          <div className="empty-state-text">
            {storesLoading ? 'Loading stores…' : 'Choose a store above to view its departments.'}
          </div>
        </div>
      )}

      {storeIdParam && (
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                {config.hasDeptHistory && (
                  <th scope="col" style={{ width: '40px' }} aria-label="Expand department history" />
                )}
                <th scope="col" style={{ width: '100px' }}>Dept #</th>
                <th scope="col">Department Name</th>
                <th scope="col" style={{ width: '160px' }}>Sales Class</th>
                {config.hasDeptHistory && (
                  <th scope="col" style={{ width: '120px' }}>Name History</th>
                )}
                {canEdit && (
                  <>
                    <th scope="col" style={{ width: '120px' }}>Last Sold</th>
                    <th scope="col" style={{ width: '200px' }}>Set By</th>
                    <th scope="col" style={{ width: '90px' }} aria-label="Actions" />
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={colSpanCount} style={{ textAlign: 'center', padding: '2.5rem' }}>
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: '0.75rem' }}>
                      <span className="spinner" aria-hidden="true" />
                      <span>Loading departments…</span>
                    </div>
                  </td>
                </tr>
              ) : filteredDepartments.length === 0 ? (
                <tr>
                  <td colSpan={colSpanCount} style={{ textAlign: 'center', padding: '2.5rem' }}>
                    <div className="empty-state">
                      <div className="empty-state-icon" aria-hidden="true">🏷️</div>
                      <div className="empty-state-text">
                        {searchText
                          ? `No departments matching "${searchText}".`
                          : `No departments found for ${currentStore?.storeName || 'this store'}.`}
                      </div>
                    </div>
                  </td>
                </tr>
              ) : (
                filteredDepartments.map((dept) => {
                  const isExpanded = expandedDeptNums.has(dept.deptNumber);
                  const historyList = dept.history || [];
                  const hasHistory = config.hasDeptHistory && historyList.length > 0;

                  return (
                    <tr key={dept.deptNumber} style={{ verticalAlign: 'middle' }}>
                      <td colSpan={colSpanCount} style={{ padding: 0 }}>
                        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                          <tbody>
                            <tr
                              onClick={() => {
                                if (hasHistory) toggleExpand(dept.deptNumber);
                              }}
                              style={hasHistory ? { cursor: 'pointer' } : {}}
                            >
                              {config.hasDeptHistory && (
                                <td style={{ width: '40px', textAlign: 'center', padding: '0.875rem 0.5rem' }}>
                                  {hasHistory ? (
                                    <button
                                      type="button"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        toggleExpand(dept.deptNumber);
                                      }}
                                      aria-expanded={isExpanded}
                                      aria-label={`${isExpanded ? 'Collapse' : 'Expand'} history for dept #${dept.deptNumber}`}
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
                                  ) : (
                                    <span style={{ color: 'var(--text-muted)' }}>–</span>
                                  )}
                                </td>
                              )}
                              <td className="mono" style={{ width: '100px', fontWeight: 600 }}>
                                #{dept.deptNumber}
                              </td>
                              <td>
                                {dept.name ? (
                                  <strong>{dept.name}</strong>
                                ) : (
                                  <span style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
                                    — not named yet
                                  </span>
                                )}
                                {canEdit && needsName(dept) && (
                                  <span className="badge badge-warning" style={{ marginLeft: '0.5rem' }}>Needs a name</span>
                                )}
                                {canEdit && dept.adminManaged && dept.posName && dept.posName !== dept.name && (
                                  <div className="field-help">POS files: {dept.posName}</div>
                                )}
                              </td>
                              <td style={{ width: '160px' }}>
                                <SalesClassBadge salesClass={dept.salesClass} />
                              </td>
                              {config.hasDeptHistory && (
                                <td style={{ width: '120px', color: 'var(--text-secondary)' }}>
                                  {historyList.length} {historyList.length === 1 ? 'name' : 'names'}
                                </td>
                              )}
                              {canEdit && (
                                <>
                                  <td style={{ width: '120px' }}>
                                    {dept.lastSold ? formatDate(dept.lastSold) : <span style={{ color: 'var(--text-muted)' }}>—</span>}
                                  </td>
                                  <td style={{ width: '200px' }}>
                                    {dept.adminManaged ? (
                                      <span title={formatDateTime(dept.adminSetAt)}>
                                        <span className="badge badge-info">Admin</span>{' '}
                                        {adminLabel(dept.adminSetBy, me?.id)}
                                      </span>
                                    ) : (
                                      <span style={{ color: 'var(--text-muted)' }}>POS files</span>
                                    )}
                                  </td>
                                  <td style={{ width: '90px', textAlign: 'right' }}>
                                    <button
                                      type="button"
                                      className="btn btn-secondary btn-sm"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        setEditing(dept);
                                      }}
                                    >
                                      Edit
                                    </button>
                                  </td>
                                </>
                              )}
                            </tr>

                            {isExpanded && hasHistory && (
                              <tr className="row-expand">
                                <td colSpan={colSpanCount} style={{ padding: '1rem 1.5rem', backgroundColor: 'var(--bg-surface-raised)', borderBottom: '1px solid var(--border-subtle)' }}>
                                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                                    <strong style={{ fontSize: '0.8125rem', color: 'var(--text-secondary)' }}>
                                      Name History for Department #{dept.deptNumber}:
                                    </strong>
                                    <table className="data-table" style={{ background: 'var(--bg-surface)' }}>
                                      <thead>
                                        <tr>
                                          <th>Historical Name</th>
                                          <th>Department Type</th>
                                          <th>First Seen</th>
                                          <th>Last Seen</th>
                                        </tr>
                                      </thead>
                                      <tbody>
                                        {historyList.map((h, hIdx) => (
                                          <tr key={hIdx}>
                                            <td>{h.name}</td>
                                            <td>{h.deptType || '—'}</td>
                                            <td>{h.firstSeen ? formatDate(h.firstSeen) : 'never loaded'}</td>
                                            <td>{h.lastSeen ? formatDate(h.lastSeen) : 'never loaded'}</td>
                                          </tr>
                                        ))}
                                      </tbody>
                                    </table>
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
      )}

      {editing && (
        <DepartmentEditModal
          storeId={storeIdParam}
          department={editing}
          onClose={() => setEditing(null)}
          onSaved={handleSaved}
        />
      )}
    </div>
  );
}

export default DepartmentsPage;
