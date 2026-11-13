import { Field } from './Field.jsx';

/**
 * StorePicker provides a standardized store dropdown selector.
 * @param {object} props
 * @param {import('../api/ingestionApi.js').Store[]} props.stores
 * @param {string|number} [props.value]
 * @param {(val: string) => void} props.onChange
 * @param {boolean} [props.includeAll=false] - Whether to include "All stores" option with value ""
 * @param {boolean} [props.activeOnly=false] - If true, show only ACTIVE stores
 * @param {boolean} [props.disabled=false]
 * @param {string} [props.label]
 * @param {string} [props.name='storeId']
 * @param {string} [props.className]
 */
export function StorePicker({
  stores = [],
  value,
  onChange,
  includeAll = false,
  activeOnly = false,
  disabled = false,
  label,
  name = 'storeId',
  className = '',
}) {
  const filtered = activeOnly ? stores.filter((s) => s.status === 'ACTIVE') : stores;

  const options = [];
  if (includeAll) {
    options.push({ value: '', label: 'All stores' });
  } else if (!value) {
    // Without this the browser shows the first store as chosen while nothing is, and picking it fires no change.
    options.push({ value: '', label: 'Choose a store…' });
  }

  for (const s of filtered) {
    options.push({
      value: String(s.storeId),
      label: `${s.storeCode}: ${s.storeName}`,
    });
  }

  return (
    <Field
      as="select"
      label={label}
      name={name}
      value={value !== undefined && value !== null ? String(value) : ''}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      options={options}
      className={className}
    />
  );
}
