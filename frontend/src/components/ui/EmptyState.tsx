interface EmptyStateProps {
  icon?: string;
  title: string;
  description?: string;
  action?: React.ReactNode;
  compact?: boolean;
}

export function EmptyState({ icon = '📋', title, description, action, compact }: EmptyStateProps) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        padding: compact ? '28px 20px' : '52px 24px',
        gap: 10,
      }}
      role="status"
      aria-label={title}
    >
      <div style={{ fontSize: compact ? 28 : 40, marginBottom: 4, lineHeight: 1 }} aria-hidden="true">
        {icon}
      </div>
      <div style={{ fontWeight: 700, fontSize: compact ? 14 : 16, color: 'var(--ink)' }}>
        {title}
      </div>
      {description && (
        <div style={{ fontSize: 13, color: 'var(--ink3)', maxWidth: 340, lineHeight: 1.5 }}>
          {description}
        </div>
      )}
      {action && <div style={{ marginTop: 8 }}>{action}</div>}
    </div>
  );
}

export function EmptyTableState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <tr>
      <td colSpan={99} style={{ padding: 0, border: 'none' }}>
        <EmptyState icon={icon} title={title} description={description} action={action} />
      </td>
    </tr>
  );
}
