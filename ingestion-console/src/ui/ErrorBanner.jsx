import { getFriendlyErrorMessage } from './format.js';

/**
 * ErrorBanner surfaces RFC 9457 Problem Details or generic errors.
 * @param {object} props
 * @param {Error|import('../api/client.js').ApiError|string|null} props.error - Error object or message
 * @param {string} [props.title] - Optional override title
 * @param {() => void} [props.onDismiss] - Optional dismiss handler
 * @param {string} [props.className]
 */
export function ErrorBanner({ error, title, onDismiss, className = '' }) {
  if (!error) return null;

  const errorMessage = typeof error === 'string' ? error : getFriendlyErrorMessage(error);
  const errorCode = typeof error === 'object' && error?.code ? error.code : null;
  const fieldErrors = typeof error === 'object' && Array.isArray(error?.fieldErrors) ? error.fieldErrors : [];

  return (
    <div className={`banner banner-danger ${className}`} role="alert" aria-live="assertive">
      <div className="banner-icon" aria-hidden="true">
        ⚠️
      </div>
      <div className="banner-content">
        {title && <div className="banner-title">{title}</div>}
        <div className="banner-text">
          {errorMessage}
          {errorCode && errorCode !== 'ERROR' && (
            <span style={{ marginLeft: '6px', opacity: 0.8, fontSize: '0.8em' }}>
              ({errorCode})
            </span>
          )}
        </div>
        {fieldErrors.length > 0 && (
          <ul className="banner-field-errors">
            {fieldErrors.map((fe, idx) => (
              <li key={idx}>
                <strong>{fe.field}:</strong> {fe.message}
              </li>
            ))}
          </ul>
        )}
      </div>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          className="modal-close-btn"
          aria-label="Dismiss error"
          style={{ alignSelf: 'flex-start' }}
        >
          ✕
        </button>
      )}
    </div>
  );
}

/**
 * SuccessBanner surfaces confirmation and success messages.
 * @param {object} props
 * @param {string} props.message
 * @param {() => void} [props.onDismiss]
 */
export function SuccessBanner({ message, onDismiss }) {
  if (!message) return null;

  return (
    <div className="banner banner-success" role="status" aria-live="polite">
      <div className="banner-icon" aria-hidden="true">
        ✓
      </div>
      <div className="banner-content">
        <div className="banner-text">{message}</div>
      </div>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          className="modal-close-btn"
          aria-label="Dismiss message"
          style={{ alignSelf: 'flex-start' }}
        >
          ✕
        </button>
      )}
    </div>
  );
}
