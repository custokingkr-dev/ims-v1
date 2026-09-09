import { useState, useEffect, useCallback } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import { fetchReorderSignals } from '../../../../api/dashboardCommandCenterApi';
import type { ReorderSignalItem, ReorderSignalsResponse } from '../../../../types/dashboardCommandCenter';

interface Props {
  open: boolean;
  onClose: () => void;
}

const ALERT_COLOR: Record<string, string> = {
  RED:    'var(--ck-color-danger)',
  YELLOW: 'var(--ck-color-warning)',
  OK:     'var(--ck-color-primary)',
};

const ALERT_BG: Record<string, string> = {
  RED:    'var(--ck-color-danger-soft)',
  YELLOW: 'var(--ck-color-warning-soft)',
  OK:     'var(--ck-color-primary-soft)',
};

function SignalRow({ item }: { item: ReorderSignalItem }) {
  const color = ALERT_COLOR[item.alertLevel] ?? 'var(--ck-status-neutral)';
  const bg    = ALERT_BG[item.alertLevel] ?? 'var(--ck-status-neutral-bg)';
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, padding: '12px 0', borderBottom: '1px solid var(--ck-border-subtle)' }}>
      <div style={{ minWidth: 52, height: 52, borderRadius: 8, background: bg, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ fontSize: 16, fontWeight: 800, color }}>{item.daysSinceLastOrder}</span>
        <span style={{ fontSize: 9, color, fontWeight: 600 }}>days ago</span>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
          <span style={{ fontSize: 14, fontWeight: 700 }}>{item.category}</span>
          <span style={{
            fontSize: 10, fontWeight: 700, padding: '2px 6px', borderRadius: 4,
            background: bg, color,
          }}>
            {item.alertLevel}
          </span>
        </div>
        <div style={{ fontSize: 12, color: 'var(--ck-text-secondary)' }}>
          Last ordered: {new Date(item.lastOrderDate).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}
        </div>
        {item.avgIntervalDays != null && (
          <div style={{ fontSize: 12, color: 'var(--ck-text-muted)' }}>
            Avg every {item.avgIntervalDays} days
            {item.predictedNextOrderDate && (
              <> · Next: <strong style={{ color }}>{new Date(item.predictedNextOrderDate).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}</strong></>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export function ReorderSignalsDrawer({ open, onClose }: Props) {
  const [data, setData] = useState<ReorderSignalsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchReorderSignals());
    } catch {
      setError('Failed to load reorder signals.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { if (open) load(); }, [open, load]);

  const redCount    = data?.items?.filter(i => i.alertLevel === 'RED').length ?? 0;
  const yellowCount = data?.items?.filter(i => i.alertLevel === 'YELLOW').length ?? 0;
  const subtitle = data
    ? `${data.items.length} categories · ${redCount} overdue · ${yellowCount} approaching`
    : undefined;

  return (
    <CommandCenterDrawer title="Inventory Reorder Signals" subtitle={subtitle} open={open} onClose={onClose}>
      {loading && <p style={{ color: 'var(--ck-text-muted)', fontSize: 13 }}>Analysing order history…</p>}
      {error && <p style={{ color: 'var(--ck-color-danger)', fontSize: 13 }}>{error}</p>}
      {data && data.items.length === 0 && (
        <p style={{ color: 'var(--ck-text-secondary)', fontSize: 13 }}>No past approved orders to analyse. Place orders to enable reorder prediction.</p>
      )}
      {data && data.items.length > 0 && (
        <>
          {data.alertCount > 0 && (
            <div style={{ display: 'flex', gap: 10, marginBottom: 16 }}>
              {redCount > 0 && (
                <div style={{ flex: 1, background: 'var(--ck-color-danger-soft)', borderRadius: 8, padding: '10px 14px' }}>
                  <div style={{ fontSize: 11, color: 'var(--ck-color-danger)', fontWeight: 700 }}>OVERDUE</div>
                  <div style={{ fontSize: 24, fontWeight: 800, color: 'var(--ck-color-danger)' }}>{redCount}</div>
                  <div style={{ fontSize: 11, color: 'var(--ck-color-danger)' }}>categories past cycle</div>
                </div>
              )}
              {yellowCount > 0 && (
                <div style={{ flex: 1, background: 'var(--ck-color-warning-soft)', borderRadius: 8, padding: '10px 14px' }}>
                  <div style={{ fontSize: 11, color: 'var(--ck-color-warning)', fontWeight: 700 }}>APPROACHING</div>
                  <div style={{ fontSize: 24, fontWeight: 800, color: 'var(--ck-color-warning)' }}>{yellowCount}</div>
                  <div style={{ fontSize: 11, color: 'var(--ck-color-warning)' }}>categories due soon</div>
                </div>
              )}
            </div>
          )}
          <div>
            {data.items.map(item => (
              <SignalRow key={item.category} item={item} />
            ))}
          </div>
        </>
      )}
    </CommandCenterDrawer>
  );
}
