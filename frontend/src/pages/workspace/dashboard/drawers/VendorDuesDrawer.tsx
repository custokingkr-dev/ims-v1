import { useState, useEffect, useCallback } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import {
  fetchVendorDues,
  markCatalogOrderVendorPaid,
  markFirefightingVendorPaid,
} from '../../../../api/dashboardCommandCenterApi';
import type { VendorDueItem, VendorDuesListResponse } from '../../../../types/dashboardCommandCenter';
import { usePermissions } from '../../../../hooks/usePermissions';

interface Props {
  open: boolean;
  onClose: () => void;
}

function rupees(paise: number): string {
  return '₹' + (paise / 100).toLocaleString('en-IN', { maximumFractionDigits: 0 });
}

// ── Confirm modal ─────────────────────────────────────────────────────────────

interface MarkPaidModalProps {
  item: VendorDueItem;
  onClose: () => void;
  onConfirm: (notes: string) => Promise<void>;
}

function MarkPaidModal({ item, onClose, onConfirm }: MarkPaidModalProps) {
  const [notes, setNotes] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit() {
    setLoading(true);
    setError(null);
    try {
      await onConfirm(notes);
      onClose();
    } catch {
      setError('Failed to record payment. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)', zIndex: 2100, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ background: 'var(--ck-bg-surface)', borderRadius: 12, padding: 24, width: 400, maxWidth: '90vw' }}>
        <h3 style={{ margin: '0 0 8px', fontSize: 16 }}>Mark Vendor Paid</h3>
        <p style={{ margin: '0 0 16px', fontSize: 13, color: 'var(--ck-text-secondary)' }}>
          Record vendor payment of <strong>{rupees(item.amountPaise)}</strong> for&nbsp;
          <strong>{item.title}</strong>
          {item.vendorName ? ` (${item.vendorName})` : ''}.
        </p>
        <label style={{ fontSize: 13, fontWeight: 600, display: 'block', marginBottom: 4 }}>Payment notes (optional)</label>
        <textarea
          value={notes}
          onChange={e => setNotes(e.target.value)}
          rows={3}
          style={{ width: '100%', borderRadius: 6, border: '1px solid var(--ck-border-default)', padding: '8px 10px', fontSize: 13, boxSizing: 'border-box', resize: 'vertical' }}
          placeholder="e.g. Paid via NEFT on 12-Jun-2026"
        />
        {error && <p style={{ color: 'var(--ck-color-danger)', fontSize: 12, margin: '8px 0 0' }}>{error}</p>}
        <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
          <button onClick={onClose} style={{ padding: '8px 16px', borderRadius: 6, border: '1px solid var(--ck-border-default)', background: 'var(--ck-bg-surface)', cursor: 'pointer', fontSize: 13 }}>Cancel</button>
          <button onClick={handleSubmit} disabled={loading} style={{ padding: '8px 16px', borderRadius: 6, border: 'none', background: 'var(--ck-color-primary)', color: 'var(--ck-text-inverse)', cursor: loading ? 'not-allowed' : 'pointer', fontSize: 13, opacity: loading ? 0.7 : 1 }}>
            {loading ? 'Saving…' : 'Confirm Payment'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Row ───────────────────────────────────────────────────────────────────────

const SOURCE_LABEL: Record<string, string> = {
  CATALOG_ORDER: 'Supply OS Order',
  FIREFIGHTING: 'Urgent Procurement',
};

function DueRow({ item, canPay, onMarkPaid }: { item: VendorDueItem; canPay: boolean; onMarkPaid: (item: VendorDueItem) => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0', borderBottom: '1px solid var(--ck-border-subtle)' }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
          <span style={{ fontSize: 10, fontWeight: 700, padding: '2px 6px', borderRadius: 4, background: item.sourceType === 'CATALOG_ORDER' ? 'var(--ck-color-accent-soft)' : 'var(--ck-color-warning-soft)', color: item.sourceType === 'CATALOG_ORDER' ? 'var(--ck-color-accent)' : 'var(--ck-color-warning)' }}>
            {SOURCE_LABEL[item.sourceType] ?? item.sourceType}
          </span>
          <span style={{ fontSize: 13, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.title}</span>
        </div>
        {item.vendorName && <div style={{ fontSize: 12, color: 'var(--ck-text-secondary)' }}>{item.vendorName}</div>}
        <div style={{ fontSize: 12, color: 'var(--ck-text-muted)' }}>{item.category} · {item.status}</div>
      </div>
      <div style={{ textAlign: 'right', minWidth: 90 }}>
        <div style={{ fontWeight: 700, fontSize: 14 }}>{rupees(item.amountPaise)}</div>
        {canPay && (
          <button
            onClick={() => onMarkPaid(item)}
            style={{ marginTop: 4, fontSize: 11, padding: '3px 8px', borderRadius: 4, border: 'none', background: 'var(--ck-color-primary)', color: 'var(--ck-text-inverse)', cursor: 'pointer' }}
          >
            Mark Paid
          </button>
        )}
      </div>
    </div>
  );
}

// ── Main drawer ───────────────────────────────────────────────────────────────

export function VendorDuesDrawer({ open, onClose }: Props) {
  const { can } = usePermissions();
  const [data, setData] = useState<VendorDuesListResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [markingItem, setMarkingItem] = useState<VendorDueItem | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchVendorDues());
    } catch {
      setError('Failed to load vendor dues.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { if (open) load(); }, [open, load]);

  async function handleMarkPaid(item: VendorDueItem, notes: string) {
    if (item.sourceType === 'CATALOG_ORDER') {
      await markCatalogOrderVendorPaid(item.id, { notes });
    } else {
      await markFirefightingVendorPaid(item.id, { notes });
    }
    await load();
  }

  const canPayOrder = can('order:update');
  const canPayFF = can('firefighting:update');

  const subtitle = data
    ? `${data.catalogOrderCount + data.firefightingCount} unpaid · ${rupees(data.totalDuesPaise)} total`
    : undefined;

  return (
    <>
      <CommandCenterDrawer title="Vendor Payment Dues" subtitle={subtitle} open={open} onClose={onClose}>
        {loading && <p style={{ color: 'var(--ck-text-muted)', fontSize: 13 }}>Loading…</p>}
        {error && <p style={{ color: 'var(--ck-color-danger)', fontSize: 13 }}>{error}</p>}
        {data && data.items.length === 0 && (
          <p style={{ color: 'var(--ck-text-secondary)', fontSize: 13 }}>No pending vendor payments. All caught up!</p>
        )}
        {data && data.items.length > 0 && (
          <>
            <div style={{ display: 'flex', gap: 12, marginBottom: 16 }}>
              <div style={{ flex: 1, background: 'var(--ck-color-accent-soft)', borderRadius: 8, padding: '10px 14px' }}>
                <div style={{ fontSize: 11, color: 'var(--ck-color-accent)', fontWeight: 600 }}>Supply OS Orders</div>
                <div style={{ fontSize: 18, fontWeight: 700 }}>{data.catalogOrderCount}</div>
                <div style={{ fontSize: 12, color: 'var(--ck-text-primary)' }}>{rupees(data.catalogOrderTotalPaise)}</div>
              </div>
              <div style={{ flex: 1, background: 'var(--ck-color-warning-soft)', borderRadius: 8, padding: '10px 14px' }}>
                <div style={{ fontSize: 11, color: 'var(--ck-color-warning)', fontWeight: 600 }}>Urgent Procurement</div>
                <div style={{ fontSize: 18, fontWeight: 700 }}>{data.firefightingCount}</div>
                <div style={{ fontSize: 12, color: 'var(--ck-text-primary)' }}>{rupees(data.firefightingTotalPaise)}</div>
              </div>
            </div>
            <div>
              {data.items.map(item => (
                <DueRow
                  key={`${item.sourceType}-${item.id}`}
                  item={item}
                  canPay={item.sourceType === 'CATALOG_ORDER' ? canPayOrder : canPayFF}
                  onMarkPaid={setMarkingItem}
                />
              ))}
            </div>
          </>
        )}
      </CommandCenterDrawer>

      {markingItem && (
        <MarkPaidModal
          item={markingItem}
          onClose={() => setMarkingItem(null)}
          onConfirm={(notes) => handleMarkPaid(markingItem, notes)}
        />
      )}
    </>
  );
}
