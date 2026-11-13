import { useId } from 'react';

/**
 * Field component encapsulates accessible form inputs, labels, errors, and help text.
 * @param {object} props
 * @param {string} [props.label] - Human label for the field
 * @param {string} [props.name] - Form field name
 * @param {string} [props.type='text'] - Input type (text, password, email, etc.)
 * @param {any} [props.value] - Input value
 * @param {(e: any) => void} [props.onChange] - Change handler
 * @param {boolean} [props.required=false] - If field is mandatory
 * @param {boolean} [props.disabled=false] - If field is disabled
 * @param {string} [props.error] - Validation error message
 * @param {string} [props.helpText] - Explanatory help text
 * @param {string} [props.placeholder] - Placeholder text
 * @param {string} [props.as='input'] - 'input' | 'select' | 'textarea' | 'custom'
 * @param {Array<{value: string|number, label: string}>} [props.options] - Options for select
 * @param {React.ReactNode} [props.children] - Children if as === 'custom' or select options
 * @param {number} [props.rows] - For textarea
 * @param {string} [props.autoComplete]
 * @param {string} [props.className]
 */
export function Field({
  label,
  name,
  type = 'text',
  value,
  onChange,
  required = false,
  disabled = false,
  error,
  helpText,
  placeholder,
  as = 'input',
  options,
  children,
  rows = 3,
  autoComplete,
  className = '',
  ...rest
}) {
  const generatedId = useId();
  const fieldId = name || generatedId;
  const errorId = `${fieldId}-error`;
  const helpId = `${fieldId}-help`;

  const ariaDescribedBy = [
    error ? errorId : null,
    helpText ? helpId : null,
  ].filter(Boolean).join(' ') || undefined;

  const hasError = Boolean(error);

  return (
    <div className={`field-group ${className}`}>
      {label && (
        <label htmlFor={fieldId} className="field-label">
          {label}
          {required && <span className="field-required" aria-hidden="true">*</span>}
        </label>
      )}

      {as === 'input' && (
        <input
          id={fieldId}
          name={name}
          type={type}
          value={value ?? ''}
          onChange={onChange}
          required={required}
          disabled={disabled}
          placeholder={placeholder}
          autoComplete={autoComplete}
          aria-invalid={hasError}
          aria-describedby={ariaDescribedBy}
          className="field-input"
          {...rest}
        />
      )}

      {as === 'select' && (
        <select
          id={fieldId}
          name={name}
          value={value ?? ''}
          onChange={onChange}
          required={required}
          disabled={disabled}
          aria-invalid={hasError}
          aria-describedby={ariaDescribedBy}
          className="field-select"
          {...rest}
        >
          {options ? options.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          )) : children}
        </select>
      )}

      {as === 'textarea' && (
        <textarea
          id={fieldId}
          name={name}
          value={value ?? ''}
          onChange={onChange}
          required={required}
          disabled={disabled}
          placeholder={placeholder}
          rows={rows}
          aria-invalid={hasError}
          aria-describedby={ariaDescribedBy}
          className="field-textarea"
          {...rest}
        />
      )}

      {as === 'custom' && children}

      {error && (
        <span id={errorId} className="field-error" role="alert">
          {error}
        </span>
      )}

      {!error && helpText && (
        <span id={helpId} className="field-help">
          {helpText}
        </span>
      )}
    </div>
  );
}
