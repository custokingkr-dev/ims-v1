import React, { useId, useRef } from 'react';
import { useDialogFocus } from '../../../../hooks/useDialogFocus';

interface Props {
  title: string;
  subtitle?: string;
  open: boolean;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
}

export function CommandCenterDrawer({ title, subtitle, open, onClose, children, footer }: Props) {
  const ref = useRef<HTMLElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  useDialogFocus(ref, open, onClose);

  if (!open) return null;

  return (
    <>
      <div
        className="ck-drawer-backdrop"
        onClick={onClose}
        role="presentation"
        aria-hidden="true"
      />
      <aside
        ref={ref}
        tabIndex={-1}
        className="ck-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={subtitle ? descriptionId : undefined}
      >
        <div className="ck-drawer-header">
          <div style={{ flex: 1, minWidth: 0 }}>
            <h2 id={titleId} className="ck-drawer-title">{title}</h2>
            {subtitle && <p id={descriptionId} className="ck-drawer-subtitle">{subtitle}</p>}
          </div>
          <button
            className="ck-drawer-close"
            onClick={onClose}
            aria-label="Close drawer"
          >
            ✕
          </button>
        </div>

        <div className="ck-drawer-body">
          {children}
        </div>

        {footer && (
          <div className="ck-drawer-footer">
            {footer}
          </div>
        )}
      </aside>
    </>
  );
}
