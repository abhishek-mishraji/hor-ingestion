import { useEffect, useState } from 'react';
import { useVendor } from '../vendors/VendorProvider.jsx';
import { useSession } from '../session/SessionProvider.jsx';
import { Modal } from './Modal.jsx';
import { Field } from './Field.jsx';
import { ErrorBanner } from './ErrorBanner.jsx';
import { formatDate, formatDateTime, formatSalesClass } from './format.js';

const NAME_MAX = 60;
const NOTE_MAX = 500;
const SOLD_LIMIT = 15;

const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const units = new Intl.NumberFormat('en-US', { maximumFractionDigits: 3 });

const LINE_TYPES = {
  ItemLine: 'Scanned',
  MerchandiseCodeLine: 'Dept ring',
  FuelLine: 'Fuel',
  FuelPrepayLine: 'Fuel prepay',
};

/**
 * What was sold in the department, so the admin can tell what it is before naming it.
 * @param {{sold: import('../api/ingestionApi.js').DepartmentItems|null, error: Error|null}} props
 */
function SoldItems({ sold, error }) {
  return (
    <div style={{ marginBottom: '1.25rem' }}>
      <strong style={{ fontSize: '0.8125rem', color: 'var(--text-secondary)' }}>What was sold</strong>
      <ErrorBanner error={error} title="Could not load the items" />
      {!sold && !error && <p className="field-help">Loading…</p>}
      {sold && sold.lines === 0 && <p className="field-help">Nothing sold in this department on the loaded days.</p>}
      {sold && sold.lines > 0 && (
        <>
          <p className="field-help" style={{ margin: '0.25rem 0 0.5rem' }}>
            {units.format(sold.lines)} lines · {units.format(sold.distinctItems)} different items ·{' '}
            {money.format(sold.amount)} on all loaded days
            {sold.distinctItems > sold.items.length && ` · top ${sold.items.length} shown`}
          </p>
          <div style={{ maxHeight: '260px', overflowY: 'auto' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Description</th>
                  <th>Code</th>
                  <th style={{ textAlign: 'right' }}>Units</th>
                  <th style={{ textAlign: 'right' }}>Amount</th>
                  <th>Last sold</th>
                </tr>
              </thead>
              <tbody>
                {sold.items.map((i, idx) => (
                  <tr key={idx}>
                    <td>
                      {i.description || <em style={{ color: 'var(--text-muted)' }}>no description</em>}
                      <div className="field-help">{LINE_TYPES[i.lineType] || i.lineType}</div>
                    </td>
                    <td className="mono">{i.posCode || '—'}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>{units.format(i.units)}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>{money.format(i.amount)}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>{formatDate(i.lastSold)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
const CLASS_OPTIONS = [
  { value: 'merchandise', label: 'Merchandise' },
  { value: 'lottery_sale', label: 'Lottery sale' },
  { value: 'lottery_payout', label: 'Lottery payout' },
];

/**
 * "admin #7", or "you" for the signed-in admin.
 * @param {number|null|undefined} adminId
 * @param {number|undefined} meId
 */
export function adminLabel(adminId, meId) {
  if (adminId === null || adminId === undefined) return '—';
  return adminId === meId ? 'you' : `admin #${adminId}`;
}

/**
 * DepartmentEditModal: a platform admin sets a department's name and sales class (they then stay whatever the POS
 * files say), or returns it to the POS name. Shows the department's change history.
 * @param {object} props
 * @param {number|string} props.storeId
 * @param {import('../api/ingestionApi.js').NormalizedDepartment} props.department
 * @param {() => void} props.onClose
 * @param {(updated: import('../api/ingestionApi.js').NormalizedDepartment) => void} props.onSaved
 */
export function DepartmentEditModal({ storeId, department, onClose, onSaved }) {
  const { api } = useVendor();
  const { me } = useSession();

  const [name, setName] = useState(department.name || department.posName || '');
  const [salesClass, setSalesClass] = useState(
    CLASS_OPTIONS.some((o) => o.value === department.salesClass) ? department.salesClass : 'merchandise'
  );
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const [changes, setChanges] = useState(/** @type {import('../api/ingestionApi.js').DepartmentChange[]|null} */ (null));
  const [changesError, setChangesError] = useState(null);
  const [sold, setSold] = useState(/** @type {import('../api/ingestionApi.js').DepartmentItems|null} */ (null));
  const [soldError, setSoldError] = useState(null);

  useEffect(() => {
    let current = true;
    api.departmentChanges(storeId, department.deptNumber)
      .then((list) => current && setChanges(list))
      .catch((err) => current && setChangesError(err));
    api.departmentItems(storeId, department.deptNumber, SOLD_LIMIT)
      .then((items) => current && setSold(items))
      .catch((err) => current && setSoldError(err));
    return () => {
      current = false;
    };
  }, [api, storeId, department.deptNumber]);

  const trimmed = name.trim();
  const nameError = trimmed.length > NAME_MAX ? `At most ${NAME_MAX} characters.` : null;
  const unchanged = department.adminManaged && trimmed === department.name && salesClass === department.salesClass;
  const canSave = trimmed.length > 0 && !nameError && note.length <= NOTE_MAX && !unchanged && !saving;

  const run = async (action) => {
    setSaving(true);
    setError(null);
    try {
      onSaved(await action());
    } catch (err) {
      setError(err);
      setSaving(false);
    }
  };

  const save = (e) => {
    e.preventDefault();
    if (!canSave) return;
    run(() => api.setDepartment(storeId, department.deptNumber, { name: trimmed, salesClass, note: note.trim() }));
  };

  const reset = () => run(() => api.resetDepartment(storeId, department.deptNumber));

  const footer = (
    <>
      {department.adminManaged && (
        <button
          type="button"
          className="btn btn-outline-danger"
          onClick={reset}
          disabled={saving}
          style={{ marginRight: 'auto' }}
          title="Use the name from the POS files again, and the sales class that name gives"
        >
          Return to automatic
        </button>
      )}
      <button type="button" className="btn btn-secondary" onClick={onClose} disabled={saving}>
        Cancel
      </button>
      <button type="submit" form="dept-edit-form" className="btn btn-primary" disabled={!canSave}>
        {saving ? 'Saving…' : 'Save'}
      </button>
    </>
  );

  return (
    <Modal isOpen onClose={saving ? () => {} : onClose} title={`Department #${department.deptNumber}`} footer={footer} maxWidth="720px">
      <div className="callout callout-warning">
        Changes merchandise and lottery totals on every report, including past days. After saving, the name and
        class stay as set here, whatever the POS files say.
      </div>

      <ErrorBanner error={error} title="Could not save the department" />

      <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', margin: '0 0 1rem' }}>
        Name in the POS files:{' '}
        {department.posName ? <strong>{department.posName}</strong> : <em>none (this department is only scanned)</em>}
        {department.adminManaged && (
          <>
            {' · '}set by {adminLabel(department.adminSetBy, me?.id)} on {formatDateTime(department.adminSetAt)}
          </>
        )}
      </p>

      <SoldItems sold={sold} error={soldError} />

      <form id="dept-edit-form" onSubmit={save}>
        <Field
          label="Name"
          name="dept-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
          maxLength={NAME_MAX}
          placeholder="e.g. LOTTO SCRATCH OFF"
          error={nameError}
          disabled={saving}
        />
        <Field
          label="Sales class"
          name="dept-class"
          as="select"
          value={salesClass}
          onChange={(e) => setSalesClass(e.target.value)}
          options={CLASS_OPTIONS}
          helpText="Lottery sales and payouts are kept out of merchandise sales. Fuel is classified by the fuel lines themselves."
          disabled={saving}
        />
        <Field
          label="Note (optional)"
          name="dept-note"
          as="textarea"
          rows={2}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          maxLength={NOTE_MAX}
          placeholder="Why, e.g. scratch-off tickets are only scanned"
          disabled={saving}
        />
      </form>

      <div style={{ marginTop: '1.25rem' }}>
        <strong style={{ fontSize: '0.8125rem', color: 'var(--text-secondary)' }}>Change history</strong>
        <ErrorBanner error={changesError} title="Could not load the history" />
        {changes === null && !changesError && <p className="field-help">Loading…</p>}
        {changes && changes.length === 0 && <p className="field-help">No admin changes yet.</p>}
        {changes && changes.length > 0 && (
          <table className="data-table" style={{ marginTop: '0.5rem' }}>
            <thead>
              <tr>
                <th>When</th>
                <th>Change</th>
                <th>By</th>
              </tr>
            </thead>
            <tbody>
              {changes.map((c) => (
                <tr key={c.changeId}>
                  <td style={{ whiteSpace: 'nowrap' }}>{formatDateTime(c.changedAt)}</td>
                  <td>
                    {c.action === 'RESET' ? 'Returned to automatic: ' : ''}
                    {c.oldName || '(no name)'} · {formatSalesClass(c.oldSalesClass)} → {c.newName || '(no name)'} ·{' '}
                    {formatSalesClass(c.newSalesClass)}
                    {c.note && <div className="field-help">{c.note}</div>}
                  </td>
                  <td>{adminLabel(c.changedBy, me?.id)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </Modal>
  );
}
