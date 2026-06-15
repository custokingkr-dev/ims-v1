import React from 'react';

interface Props {
  value: number | string;
  label: string;
  variant?: 'default' | 'warn' | 'danger' | 'ok';
}

const VARIANT_STYLE: Record<string, React.CSSProperties> = {
  default: { background: 'var(--surface-2)', color: 'var(--text)' },
  warn:    { background: '#fff3cd', color: '#856404' },
  danger:  { background: '#fde8e8', color: '#c0312b' },
  ok:      { background: '#e6f4ed', color: '#1a6840' },
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
