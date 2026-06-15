import React from 'react';
import { MetricBadge } from './MetricBadge';

interface InsightMetric {
  value: number | string;
  label: string;
  variant?: 'default' | 'warn' | 'danger' | 'ok';
}

interface Props {
  title: string;
  description: string;
  metrics: InsightMetric[];
  ctaLabel?: string;
  onCta?: () => void;
  disabled?: boolean;
  module: 'fees' | 'attendance' | 'students' | 'photography' | 'orders';
}

const MODULE_ACCENT: Record<string, string> = {
  fees:        '#1a6840',
  attendance:  '#b35c00',
  students:    '#1a4fa8',
  photography: '#5b2d8a',
  orders:      '#6a3d9a',
};

export function ActionInsightCard({
  title, description, metrics, ctaLabel, onCta, disabled = false, module,
}: Props) {
  const accent = MODULE_ACCENT[module] ?? 'var(--text-muted)';

  return (
    <div
      style={{
        background: 'var(--surface)',
        border: '1px solid var(--border)',
        borderRadius: 8,
        padding: '14px 16px',
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        borderLeft: `3px solid ${accent}`,
        opacity: disabled ? 0.6 : 1,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)' }}>{title}</span>
        {!disabled && ctaLabel && onCta && (
          <button
            className="ck-btn ck-btn-ghost"
            style={{ fontSize: 12, padding: '2px 10px', flexShrink: 0 }}
            onClick={onCta}
          >
            {ctaLabel}
          </button>
        )}
      </div>
      <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: 0, lineHeight: 1.4 }}>
        {description}
      </p>
      {metrics.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {metrics.map((m, i) => (
            <MetricBadge key={i} value={m.value} label={m.label} variant={m.variant} />
          ))}
        </div>
      )}
      {disabled && (
        <span style={{ fontSize: 11, color: 'var(--text-muted)', fontStyle: 'italic' }}>
          Coming in a future phase
        </span>
      )}
    </div>
  );
}
