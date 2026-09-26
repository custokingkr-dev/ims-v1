import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { assertProcurementRecoveryUnchanged, readProcurementRecovery, withProcurementRecoveryLock, writeProcurementRecovery, type ProcurementRecovery } from './firefightingSaveRecovery';

const key = 'ck:procurement-save:v1:77:10:new';
const pending: ProcurementRecovery = { version: 1, code: null, pending: { kind: 'request', code: null, payload: { title: 'Synthetic request', idempotencyKey: 'original-key' } } };

function browserLocks() {
  const active = new Set<string>();
  const request = vi.fn(async (name: string, options: LockOptions, callback: (lock: Lock | null) => unknown) => {
    expect(options).toEqual({ mode: 'exclusive', ifAvailable: true });
    if (active.has(name)) return callback(null);
    active.add(name);
    try { return await callback({ name, mode: 'exclusive' } as Lock); }
    finally { active.delete(name); }
  });
  vi.stubGlobal('navigator', { locks: { request } });
  return request;
}

describe('Procurement recovery coordination between tabs', () => {
  beforeEach(() => { localStorage.clear(); });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  it('holds the same-record lock through async completion and refuses concurrent saves', async () => {
    const request = browserLocks();
    let release!: (result: string) => void;
    const network = new Promise<string>(resolve => { release = resolve; });
    const first = withProcurementRecoveryLock(key, () => network);
    const duplicate = vi.fn();
    await expect(withProcurementRecoveryLock(key, duplicate)).rejects.toThrow('saving in another tab');
    expect(duplicate).not.toHaveBeenCalled();
    release('saved');
    await expect(first).resolves.toBe('saved');
    await expect(withProcurementRecoveryLock(key, () => 'recovered')).resolves.toBe('recovered');
    expect(request).toHaveBeenCalledWith(`custoking-procurement:${key}`, { mode: 'exclusive', ifAvailable: true }, expect.any(Function));
  });

  it('releases failed operations without changing their error or recovery record', async () => {
    browserLocks(); writeProcurementRecovery(key, pending);
    const lostResponse = new Error('Response lost');
    await expect(withProcurementRecoveryLock(key, () => Promise.reject(lostResponse))).rejects.toBe(lostResponse);
    expect(readProcurementRecovery(key)).toEqual(pending);
    await expect(withProcurementRecoveryLock(key, () => 'retry original')).resolves.toBe('retry original');
  });

  it('does not serialize unrelated user or school recovery records', async () => {
    browserLocks();
    let release!: () => void;
    const first = withProcurementRecoveryLock(key, () => new Promise<void>(resolve => { release = resolve; }));
    await expect(withProcurementRecoveryLock('ck:procurement-save:v1:88:20:new', () => 'independent')).resolves.toBe('independent');
    release(); await first;
  });

  it('fails closed without browser lock support and never calls the save operation', async () => {
    vi.stubGlobal('navigator', {});
    const operation = vi.fn();
    await expect(withProcurementRecoveryLock(key, operation)).rejects.toThrow('Safe procurement saving is unavailable');
    expect(operation).not.toHaveBeenCalled(); expect(localStorage.getItem(key)).toBeNull();
  });

  it('rejects changed, newly inserted, and cleared records before a stale tab can overwrite them', () => {
    expect(() => assertProcurementRecoveryUnchanged(key, null)).not.toThrow();
    writeProcurementRecovery(key, pending);
    expect(() => assertProcurementRecoveryUnchanged(key, null)).toThrow('changed in another tab');
    const restored = readProcurementRecovery(key);
    expect(() => assertProcurementRecoveryUnchanged(key, restored)).not.toThrow();
    const other = { ...pending, pending: { ...pending.pending!, payload: { title: 'Another request', idempotencyKey: 'other-key' } } };
    writeProcurementRecovery(key, other);
    expect(() => assertProcurementRecoveryUnchanged(key, restored)).toThrow('recovery record was kept');
    expect(readProcurementRecovery(key)).toEqual(other);
    localStorage.removeItem(key);
    expect(() => assertProcurementRecoveryUnchanged(key, other)).toThrow('changed in another tab');
  });

  it('preserves malformed recovery data and blocks replacement rather than assuming an empty record', () => {
    localStorage.setItem(key, '{bad json');
    expect(() => assertProcurementRecoveryUnchanged(key, null)).toThrow();
    expect(localStorage.getItem(key)).toBe('{bad json');
  });
});
