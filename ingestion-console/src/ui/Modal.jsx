import { useEffect, useRef, useId } from 'react';

/**
 * Native HTML <dialog> modal component.
 * @param {object} props
 * @param {boolean} props.isOpen - Controls dialog visibility
 * @param {() => void} props.onClose - Close callback
 * @param {string} props.title - Modal title
 * @param {React.ReactNode} props.children - Modal body
 * @param {React.ReactNode} [props.footer] - Optional modal footer buttons
 * @param {string} [props.maxWidth] - Custom max-width
 */
export function Modal({
  isOpen,
  onClose,
  title,
  children,
  footer,
  maxWidth,
}) {
  const dialogRef = useRef(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    if (isOpen) {
      if (!dialog.open) {
        dialog.showModal();
      }
    } else {
      if (dialog.open) {
        dialog.close();
      }
    }
  }, [isOpen]);

  // Handle native ESC key close
  const handleCancel = (e) => {
    e.preventDefault();
    onClose();
  };

  // Close when clicking directly on the backdrop
  const handleBackdropClick = (e) => {
    if (e.target === dialogRef.current) {
      onClose();
    }
  };

  return (
    <dialog
      ref={dialogRef}
      onCancel={handleCancel}
      onClick={handleBackdropClick}
      aria-labelledby={titleId}
      className="modal-dialog"
      style={maxWidth ? { maxWidth } : undefined}
    >
      <div className="modal-header">
        <h2 id={titleId} className="modal-title">
          {title}
        </h2>
        <button
          type="button"
          onClick={onClose}
          className="modal-close-btn"
          aria-label="Close dialog"
        >
          ✕
        </button>
      </div>

      <div className="modal-body">
        {children}
      </div>

      {footer && (
        <div className="modal-footer">
          {footer}
        </div>
      )}
    </dialog>
  );
}
