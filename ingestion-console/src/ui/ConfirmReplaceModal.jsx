import { useState, useEffect } from 'react';
import { Modal } from './Modal.jsx';
import { Field } from './Field.jsx';

/**
 * ConfirmReplaceModal requires typing the exact date to confirm REPLACE fetch.
 * @param {object} props
 * @param {boolean} props.isOpen
 * @param {string} props.date - YYYY-MM-DD
 * @param {string} props.storeCode
 * @param {() => Promise<void>|void} props.onConfirm
 * @param {() => void} props.onClose
 * @param {boolean} [props.busy=false]
 */
export function ConfirmReplaceModal({
  isOpen,
  date,
  storeCode,
  onConfirm,
  onClose,
  busy = false,
}) {
  const [typed, setTyped] = useState('');

  useEffect(() => {
    if (isOpen) {
      setTyped('');
    }
  }, [isOpen, date]);

  const isConfirmed = typed === date;

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={`Replace ${date}?`}
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem', width: '100%' }}>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="btn btn-secondary"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={!isConfirmed || busy}
            className="btn btn-danger"
          >
            {busy ? (
              <>
                <span className="spinner" aria-hidden="true" style={{ width: 14, height: 14 }} />
                <span>Replacing…</span>
              </>
            ) : (
              'Replace day'
            )}
          </button>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
        <p style={{ color: 'var(--text-secondary)', lineHeight: 1.5 }}>
          This deletes everything loaded for <strong>{storeCode}</strong> on <strong>{date}</strong> and loads it again from S3. This cannot be undone.
        </p>

        <Field
          label={`To confirm, type the date (${date})`}
          name="confirmDate"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          placeholder="YYYY-MM-DD"
          disabled={busy}
          autoComplete="off"
        />
      </div>
    </Modal>
  );
}
