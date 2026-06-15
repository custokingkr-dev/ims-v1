import React, { useEffect } from 'react';

interface Props {
  title: string;
  subtitle?: string;
  open: boolean;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
}

export function CommandCenterDrawer({ title, subtitle, open, onClose, children, footer }: Props) {
  // Close on Escape
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose]);

  // Body scroll lock while open
  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [open]);

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
        className="ck-drawer"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="ck-drawer-header">
          <div style={{ flex: 1, minWidth: 0 }}>
            <h2 className="ck-drawer-title">{title}</h2>
            {subtitle && <p className="ck-drawer-subtitle">{subtitle}</p>}
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
