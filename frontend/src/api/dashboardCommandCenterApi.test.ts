import { beforeEach, describe, expect, it, vi } from 'vitest';

const get = vi.fn();
const getAuthSessionVersion = vi.fn(() => 1);

vi.mock('../services/api', () => ({
  default: { get },
  getAuthSessionVersion,
}));

describe('fetchCommandCenterMetrics', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-05T00:00:00Z'));
    get.mockReset();
    getAuthSessionVersion.mockReturnValue(1);
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  });

  it('deduplicates concurrent and repeated dashboard requests', async () => {
    get.mockResolvedValue({ data: { metrics: [] } });
    const { fetchCommandCenterMetrics } = await import('./dashboardCommandCenterApi');

    const [first, second] = await Promise.all([
      fetchCommandCenterMetrics(),
      fetchCommandCenterMetrics(),
    ]);
    const cached = await fetchCommandCenterMetrics();

    expect(get).toHaveBeenCalledTimes(1);
    expect(first).toBe(second);
    expect(cached).toBe(first);
  });

  it('refreshes after the cache interval while the tab is visible', async () => {
    get.mockResolvedValueOnce({ data: { version: 1 } }).mockResolvedValueOnce({ data: { version: 2 } });
    const { fetchCommandCenterMetrics } = await import('./dashboardCommandCenterApi');

    await fetchCommandCenterMetrics();
    vi.advanceTimersByTime(55_001);
    const refreshed = await fetchCommandCenterMetrics();

    expect(get).toHaveBeenCalledTimes(2);
    expect(refreshed).toEqual({ version: 2 });
  });

  it('serves stale cached data while the tab is hidden', async () => {
    get.mockResolvedValue({ data: { version: 1 } });
    const { fetchCommandCenterMetrics } = await import('./dashboardCommandCenterApi');

    await fetchCommandCenterMetrics();
    vi.advanceTimersByTime(120_000);
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    await fetchCommandCenterMetrics();

    expect(get).toHaveBeenCalledTimes(1);
  });

  it('does not reuse data after the authenticated session changes', async () => {
    get.mockResolvedValueOnce({ data: { version: 1 } }).mockResolvedValueOnce({ data: { version: 2 } });
    const { fetchCommandCenterMetrics } = await import('./dashboardCommandCenterApi');

    await fetchCommandCenterMetrics();
    getAuthSessionVersion.mockReturnValue(2);
    const refreshed = await fetchCommandCenterMetrics();

    expect(get).toHaveBeenCalledTimes(2);
    expect(refreshed).toEqual({ version: 2 });
  });

  it('does not share an in-flight request across authenticated sessions', async () => {
    let resolveFirst!: (value: { data: { version: number } }) => void;
    get.mockReturnValueOnce(new Promise(resolve => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({ data: { version: 2 } });
    const { fetchCommandCenterMetrics } = await import('./dashboardCommandCenterApi');

    const firstSessionRequest = fetchCommandCenterMetrics();
    getAuthSessionVersion.mockReturnValue(2);
    const secondSessionResult = await fetchCommandCenterMetrics();
    resolveFirst({ data: { version: 1 } });
    await firstSessionRequest;

    expect(get).toHaveBeenCalledTimes(2);
    expect(secondSessionResult).toEqual({ version: 2 });
  });
});
