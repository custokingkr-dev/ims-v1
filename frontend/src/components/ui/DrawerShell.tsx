import { useEffect } from 'react';

interface DrawerShellProps {
  open: boolean;
  onClose: () => void;
  title: string;
  subtitle?: string;
  icon?: string;
  iconBg?: string;
  iconColor?: string;
  size?: 'default' | 'lg';
  footer?: React.ReactNode;
  children: React.ReactNode;
}

export function DrawerShell({
  open,
  onClose,
  title,
  subtitle,
  icon,
  iconBg = 'var(--bg)',
  iconColor = 'var(--ink2)',
  size = 'default',
  footer,
  children,
}: DrawerShellProps) {
  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => {
      document.body.style.overflow = '';
    };
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
      <div
        className={`ck-drawer${size === 'lg' ? ' ck-drawer-lg' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="ck-drawer-header">
          {icon && (
            <div
              className="ck-drawer-header-icon"
              style={{ background: iconBg, color: iconColor }}
              aria-hidden="true"
            >
              {icon}
            </div>
          )}
          <div style={{ flex: 1, minWidth: 0 }}>
            <h2 className="ck-drawer-title">{title}</h2>
            {subtitle && <p className="ck-drawer-subtitle">{subtitle}</p>}
          </div>
          <button
            className="ck-drawer-close"
            onClick={onClose}
            aria-label="Close drawer"
          >
            ×
          </button>
        </div>

        <div className="ck-drawer-body">{children}</div>

        {footer && <div className="ck-drawer-footer">{footer}</div>}
      </div>
    </>
  );
}
