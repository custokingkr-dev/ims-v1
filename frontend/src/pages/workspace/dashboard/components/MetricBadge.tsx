import React from 'react';

interface Props {
  value: number | string;
  label: string;
  variant?: 'default' | 'warn' | 'danger' | 'ok';
}

const VARIANT_STYLE: Record<string, React.CSSProperties> = {
  default: { background: 'var(--surface-2)', color: 'var(--text)' },
  warn:    { background: 'var(--ck-color-warning-soft)', color: 'var(--ck-color-warning)' },
  danger:  { background: 'var(--ck-color-danger-soft)', color: 'var(--ck-color-danger)' },
  ok:      { background: 'var(--ck-color-primary-soft)', color: 'var(--ck-color-primary)' },
};

export function MetricBadge({ value, label, variant = 'default' }: Props) {
  return (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 4,
        padding: '2px 8px', borderRadius: 4, fontSize: 12,
        fontWeight: 600, whiteSpace: 'nowrap',
        ...VARIANT_STYLE[variant],
      }}
      title={label}
    >
      {value} <span style={{ fontWeight: 400, opacity: 0.8 }}>{label}</span>
    </span>
  );
}
