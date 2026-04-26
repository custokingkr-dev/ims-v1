// Presentational components and style constants shared across workspace panels.
// These components are stateless — they render props, nothing more.

import { type CSSProperties } from 'react';
import { formatMoney } from './utils';

// ─── Layout shells ────────────────────────────────────────────────────────────

export function ModuleShell({
  title, subtitle, actions, children,
}: {
  title: string;
  subtitle: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <>
      <div className="ck-ph">
        <div className="ck-ph-l"><h1>{title}</h1><p>{subtitle}</p></div>
        <div className="ck-actions-inline">{actions}</div>
      </div>
      {children}
    </>
  );
}

// ─── Form helpers ───────────���──────────────────────────────���──────────────────

export function Field({
  label, children, hint, error, style,
}: {
  label: string;
  children: React.ReactNode;
  hint?: string;
  error?: string;
  style?: CSSProperties;
}) {
  return (
    <div className="ck-field" style={style}>
      <label>{label}</label>
      {children}
      {error
        ? <div className="ts" style={{ marginTop: 4, color: '#b42318' }}>{error}</div>
        : hint
          ? <div className="ts" style={{ marginTop: 4 }}>{hint}</div>
          : null}
    </div>
  );
}

export function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="ck-info">
      <div className="ck-info-l">{label}</div>
      <div className="ck-info-v">{value}</div>
    </div>
  );
}

// ─── Stat card ────────────────────���───────────────────────────────────────────

export function Stat({
  label, value, sub, pill, tone, onClick,
}: {
  label: string;
  value: string | number;
  sub: string;
  pill: string;
  tone: 'green' | 'blue' | 'orange' | 'red';
  onClick?: () => void;
}) {
  const toneClass = tone === 'green' ? 'pg' : tone === 'blue' ? 'pb' : tone === 'orange' ? 'po' : 'pr';
  return (
    <button className="ck-stat" onClick={onClick}>
      <div className="ck-stat-l">{label}</div>
      <div className="ck-stat-v">{value}</div>
      <div className="ck-stat-s">{sub}</div>
      <div className={`ck-pill ${toneClass}`}>{pill}</div>
    </button>
  );
}

// ─── Catalog order summary sidebar ───────────────────────────────────────────

export function OrderSummaryPanel({
  accentVar, borderVar, lines, total, delivery, onPlace, onDraft, saving,
}: {
  accentVar: string;
  borderVar: string;
  lines: Array<{ label: string; value: number }>;
  total: number;
  delivery: string;
  onPlace: () => void;
  onDraft: () => void;
  saving: boolean;
}) {
  return (
    <div className="ck-card" style={{ borderColor: borderVar, alignSelf: 'start', position: 'sticky', top: 88 }}>
      <div className="ck-form-head" style={{ color: accentVar }}>Order summary</div>
      <div className="ck-form-body">
        {lines.map((line, idx) => (
          <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, fontSize: 13, marginBottom: 8 }}>
            <span style={{ color: 'var(--ink2)' }}>{line.label}</span>
            <strong>₹{formatMoney(line.value)}</strong>
          </div>
        ))}
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, paddingTop: 12, marginTop: 12, borderTop: '1px solid var(--border)', fontSize: 15 }}>
          <span>Total</span>
          <strong style={{ color: accentVar }}>₹{formatMoney(total)}</strong>
        </div>
        <div className="ts" style={{ marginTop: 10 }}>Estimated delivery: {delivery}</div>
        <div style={{ display: 'grid', gap: 10, marginTop: 16 }}>
          <button className="ck-btn ck-btn-g" disabled={saving} onClick={onPlace}>
            {saving ? 'Saving…' : 'Place order →'}
          </button>
          <button className="ck-btn ck-btn-ghost" disabled={saving} onClick={onDraft}>
            {saving ? 'Saving…' : 'Save as draft'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── Shared style constants ──────────��────────────────────────────────────────

export const thStyle: CSSProperties = { textAlign: 'left', padding: '10px 12px', fontSize: 12, color: 'var(--ink2)' };
export const tdStyle: CSSProperties = { padding: '10px 12px', borderTop: '1px solid var(--border)', fontSize: 13, verticalAlign: 'middle' };
export const inlineInputStyle: CSSProperties = { width: '100%', border: '1px solid var(--border2)', borderRadius: 8, padding: '6px 8px', fontSize: 12, fontFamily: 'DM Sans, sans-serif' };
