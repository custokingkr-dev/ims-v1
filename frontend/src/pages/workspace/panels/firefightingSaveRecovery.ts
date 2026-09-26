export type PendingProcurementSave = {
  kind: 'request' | 'quotation' | 'submit';
  code: string | null;
  payload: Record<string, string | number | null>;
  quoteIndex?: number;
  fileName?: string;
};
export type ProcurementRecovery = { version: 1; code: string | null; pending: PendingProcurementSave | null };

export function readProcurementRecovery(key: string): ProcurementRecovery | null {
  const raw = localStorage.getItem(key);
  if (!raw) return null;
  const value = JSON.parse(raw) as ProcurementRecovery;
  const pending = value?.pending;
  if (value?.version !== 1 || !(value.code === null || typeof value.code === 'string')
      || (pending !== null && (!pending || !['request', 'quotation', 'submit'].includes(pending.kind)
        || !(pending.code === null || typeof pending.code === 'string')
        || !pending.payload || typeof pending.payload !== 'object' || Array.isArray(pending.payload)
        || Object.values(pending.payload).some(item => item !== null && typeof item !== 'string' && typeof item !== 'number')
        || (pending.kind !== 'submit' && (typeof pending.payload.idempotencyKey !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(pending.payload.idempotencyKey)))
        || (pending.quoteIndex !== undefined && (!Number.isInteger(pending.quoteIndex) || pending.quoteIndex < 0 || pending.quoteIndex > 2))
        || (pending.fileName !== undefined && typeof pending.fileName !== 'string')
        || (pending.kind !== 'request' && !pending.code)))) {
    throw new Error('Saved procurement recovery data is not valid. Review your requests before clearing this browser storage.');
  }
  return value;
}
export function writeProcurementRecovery(key: string, value: ProcurementRecovery) {
  localStorage.setItem(key, JSON.stringify(value));
}
export function clearProcurementRecovery(key: string) { localStorage.removeItem(key); }

/** Hold across the complete save/recovery operation, including its final storage cleanup. */
export async function withProcurementRecoveryLock<T>(key: string, operation: () => T | Promise<T>): Promise<T> {
  if (typeof navigator === 'undefined' || !navigator.locks?.request) {
    throw new Error('Safe procurement saving is unavailable in this browser. Use a supported browser with secure browser locks, then reopen this request. No new save was sent.');
  }
  return navigator.locks.request(`custoking-procurement:${key}`, { mode: 'exclusive', ifAvailable: true }, async lock => {
    if (!lock) {
      throw new Error('This procurement form is saving in another tab. Wait for that save to finish, then reopen this form to check its result. No new save was sent.');
    }
    return operation();
  });
}

/** Call under withProcurementRecoveryLock before replacing or removing a recovery record. */
export function assertProcurementRecoveryUnchanged(key: string, expected: ProcurementRecovery | null): void {
  const actual = readProcurementRecovery(key);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error('The saved procurement result changed in another tab. Reopen this form to recover that result before making another save. The other tab\'s recovery record was kept.');
  }
}
