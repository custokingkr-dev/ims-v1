// Pure utility functions shared across workspace panels.
// No React state dependencies — safe to import anywhere.

export const EVENT_RATES: Record<string, number> = {
  Trophy: 500,
  Medal: 150,
  Certificate: 20,
  'Banner/Backdrop': 800,
  Standee: 600,
  Brochure: 15,
};

export function formatMoney(value: any): string {
  const n = typeof value === 'number' ? value : Number(value || 0);
  return new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
}

export function formatPaise(value: any): string {
  return formatMoney(Number(value || 0) / 100);
}

export function paiseToRupeeInput(value: any): string {
  return (Number(value || 0) / 100).toFixed(2);
}

export function formatLakh(value: any): string {
  const n = typeof value === 'number' ? value : Number(value || 0);
  return n >= 100000 ? `${(n / 100000).toFixed(1)}L` : formatMoney(n);
}

export function todayIso(): string {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

/**
 * Indian financial-year labels ("2026–27") starting from the current FY.
 * FY runs April–March, so before April the current FY started the previous year.
 * Returns `count` consecutive years, current first (e.g. ["2026–27","2027–28",…]).
 */
export function financialYearOptions(count = 4): string[] {
  const now = new Date();
  const startYear = now.getMonth() + 1 >= 4 ? now.getFullYear() : now.getFullYear() - 1;
  return Array.from({ length: count }, (_, i) => {
    const s = startYear + i;
    return `${s}–${String(s + 1).slice(-2)}`;
  });
}

export function formatAddress(address: any): string {
  if (!address) return '—';
  return (
    [address.houseNumber, address.street, address.locality, address.city, address.state, address.pinCode]
      .filter(Boolean)
      .join(', ') || '—'
  );
}

export function initials(name: string): string {
  return (
    name
      .split(' ')
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() || '')
      .join('') || 'ST'
  );
}

export function formatLongDate(value?: string): string {
  if (!value) return '—';
  return new Date(`${value}T00:00:00`).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });
}

export function splitCsvLine(line: string): string[] {
  const values: string[] = [];
  let current = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i += 1) {
    const char = line[i];
    if (char === '"') {
      if (inQuotes && line[i + 1] === '"') {
        current += '"';
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (char === ',' && !inQuotes) {
      values.push(current.trim());
      current = '';
    } else {
      current += char;
    }
  }
  values.push(current.trim());
  return values;
}

export function defaultPlanName(className?: string): string {
  const value = String(className || '');
  if (value.includes('11') || value.includes('12')) return 'Class 11–12 · ₹56,000';
  if (value.includes('9') || value.includes('10')) return 'Class 9–10 · ₹48,000';
  if (value.includes('6') || value.includes('7') || value.includes('8')) return 'Class 6–8 · ₹44,000';
  return 'Class 1–5 · ₹40,000';
}

export function computeSaOrderValue(cat: string, form: any, eventItems: any[]): number {
  const sizes = ['xxs', 'xs', 's', 'm', 'l', 'xl', 'xxl'];
  if (cat === 'UNIFORMS') return sizes.reduce((sum, key) => sum + (Number(form[`size_${key}`]) || 0), 0) * 300;
  if (cat === 'NOTEBOOKS')
    return (form.notebookRows ?? []).reduce((sum: number, row: any) => sum + (Number(row.qty) || 0), 0) * 40;
  if (cat === 'IDCARDS') return ((Number(form.studentCount) || 0) + (Number(form.staffCount) || 0)) * 60;
  if (cat === 'STATIONERY') return (Number(form.kitQty) || 0) * 90;
  if (cat === 'HOUSEKEEPING')
    return (Number(form.monthlyRate) || 0) * (parseInt(form.duration || '0', 10) || 0);
  if (cat === 'EVENTS')
    return eventItems.reduce((sum, row) => sum + (Number(row.qty) || 0) * (EVENT_RATES[row.type] || 0), 0);
  if (cat === 'CUSTOM') return Number(form.budget) || 0;
  return 0;
}

export function toPaise(rupees: number): number {
  return Math.round(rupees * 100);
}

export function prettyOrderStatus(status?: string): string {
  const value = String(status || '').toUpperCase();
  if (value === 'DESIGN_APPROVAL') return 'Design approval';
  if (value === 'DESIGN_APPROVED_PROCESSING') return 'Design approved · Order in processing';
  if (value === 'AWAITING_APPROVAL') return 'Awaiting approval';
  if (value === 'PROCESSING') return 'Processing';
  if (value === 'APPROVED') return 'Approved';
  if (value === 'DELIVERED') return 'Delivered';
  return value.replace(/_/g, ' ');
}

/**
 * A catalog order's line items live inside its `orderData` JSON blob (the backend exposes it as an
 * opaque string), so the orders list can't read a top-level `items` field. This parses that blob
 * and produces a short human-readable summary for the Items column, handling the per-category item
 * shapes (name|type, qty|perKit) and the categories that have no line-item array (ID cards, etc.).
 */
export function orderItemsSummary(orderData?: unknown): string {
  if (!orderData) return '';
  let parsed: any = orderData;
  if (typeof orderData === 'string') {
    try { parsed = JSON.parse(orderData); } catch { return ''; }
  }
  const items = Array.isArray(parsed?.items) ? parsed.items : [];
  if (!items.length) {
    if (parsed?.studentCount != null) return `${parsed.studentCount} students`;
    if (parsed?.numKits != null) return `${parsed.numKits} kits`;
    if (parsed?.staffRequired != null) return `${parsed.staffRequired} staff`;
    return '';
  }
  const label = (it: any) => {
    const name = it?.name || it?.type || 'Item';
    const qty = it?.qty ?? it?.perKit;
    return qty != null ? `${name} ×${qty}` : String(name);
  };
  const shown = items.slice(0, 2).map(label);
  const extra = items.length - shown.length;
  return shown.join(', ') + (extra > 0 ? ` +${extra} more` : '');
}
