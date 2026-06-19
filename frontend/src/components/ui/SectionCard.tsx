interface SectionCardProps {
  title?: string;
  subtitle?: string;
  actions?: React.ReactNode;
  noPad?: boolean;
  children: React.ReactNode;
}

export function SectionCard({ title, subtitle, actions, noPad, children }: SectionCardProps) {
  return (
    <div className="ck-card">
      {(title || actions) && (
        <div className="ck-card-h">
          <div>
            {title && <div className="ck-card-t">{title}</div>}
            {subtitle && (
              <div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 2 }}>{subtitle}</div>
            )}
          </div>
          {actions && (
            <div className="ck-actions-inline" style={{ flexShrink: 0 }}>
              {actions}
            </div>
          )}
        </div>
      )}
      {noPad ? children : <div className="ck-form-body">{children}</div>}
    </div>
  );
}
