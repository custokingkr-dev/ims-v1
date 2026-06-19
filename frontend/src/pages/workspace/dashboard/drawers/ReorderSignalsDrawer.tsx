import React, { useState, useEffect, useCallback } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import { fetchReorderSignals } from '../../../../api/dashboardCommandCenterApi';
import type { ReorderSignalItem, ReorderSignalsResponse } from '../../../../types/dashboardCommandCenter';

interface Props {
  open: boolean;
  onClose: () => void;
}

const ALERT_COLOR: Record<string, string> = {
  RED:    '#c0312b',
  YELLOW: '#b35c00',
  OK:     '#1a6840',
};

const ALERT_BG: Record<string, string> = {
  RED:    '#fdecea',
  YELLOW: '#fff3e0',
  OK:     '#e8f5e9',
};

function SignalRow({ item }: { item: ReorderSignalItem }) {
  const color = ALERT_COLOR[item.alertLevel] ?? '#444';
  const bg    = ALERT_BG[item.alertLevel] ?? '#f5f5f5';
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, padding: '12px 0', borderBottom: '1px solid #f0f0f0' }}>
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
        <div style={{ fontSize: 12, color: '#666' }}>
          Last ordered: {new Date(item.lastOrderDate).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}
        </div>
        {item.avgIntervalDays != null && (
          <div style={{ fontSize: 12, color: '#888' }}>
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

  const redCount    = data?.items.filter(i => i.alertLevel === 'RED').length ?? 0;
  const yellowCount = data?.items.filter(i => i.alertLevel === 'YELLOW').length ?? 0;
  const subtitle = data
    ? `${data.items.length} categories · ${redCount} overdue · ${yellowCount} approaching`
    : undefined;

  return (
    <CommandCenterDrawer title="Inventory Reorder Signals" subtitle={subtitle} open={open} onClose={onClose}>
      {loading && <p style={{ color: '#888', fontSize: 13 }}>Analysing order history…</p>}
      {error && <p style={{ color: '#c0312b', fontSize: 13 }}>{error}</p>}
      {data && data.items.length === 0 && (
        <p style={{ color: '#555', fontSize: 13 }}>No past approved orders to analyse. Place orders to enable reorder prediction.</p>
      )}
      {data && data.items.length > 0 && (
        <>
          {data.alertCount > 0 && (
            <div style={{ display: 'flex', gap: 10, marginBottom: 16 }}>
              {redCount > 0 && (
                <div style={{ flex: 1, background: '#fdecea', borderRadius: 8, padding: '10px 14px' }}>
                  <div style={{ fontSize: 11, color: '#c0312b', fontWeight: 700 }}>OVERDUE</div>
                  <div style={{ fontSize: 24, fontWeight: 800, color: '#c0312b' }}>{redCount}</div>
                  <div style={{ fontSize: 11, color: '#c0312b' }}>categories past cycle</div>
                </div>
              )}
              {yellowCount > 0 && (
                <div style={{ flex: 1, background: '#fff3e0', borderRadius: 8, padding: '10px 14px' }}>
                  <div style={{ fontSize: 11, color: '#b35c00', fontWeight: 700 }}>APPROACHING</div>
                  <div style={{ fontSize: 24, fontWeight: 800, color: '#b35c00' }}>{yellowCount}</div>
                  <div style={{ fontSize: 11, color: '#b35c00' }}>categories due soon</div>
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
