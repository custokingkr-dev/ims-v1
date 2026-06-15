interface PageHeaderProps {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
  meta?: React.ReactNode;
}

export function PageHeader({ title, subtitle, actions, meta }: PageHeaderProps) {
  return (
    <div className="ck-ph">
      <div className="ck-ph-l">
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
        {meta && <div style={{ marginTop: 6 }}>{meta}</div>}
      </div>
      {actions && <div className="ck-actions-inline">{actions}</div>}
    </div>
  );
}
