import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The module remembers that a reload is under way, so every test starts from a fresh copy.
async function load() {
  vi.resetModules();
  return import('./staleChunkReload');
}

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => { values.delete(key); },
    setItem: (key, value) => { values.set(key, String(value)); },
  };
}

describe('stale chunk detection', () => {
  it('recognises a failed lazy chunk in each browser and a failed CSS preload', async () => {
    const { isStaleChunkError } = await load();
    expect(isStaleChunkError(new TypeError('Failed to fetch dynamically imported module: https://x/assets/HomePanel-B1k_VtHD.js'))).toBe(true);
    expect(isStaleChunkError(new TypeError('error loading dynamically imported module: https://x/assets/a.js'))).toBe(true);
    expect(isStaleChunkError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isStaleChunkError(new Error('Unable to preload CSS for /assets/HomePanel-abc.css'))).toBe(true);
    expect(isStaleChunkError(new Error('Cannot read properties of undefined'))).toBe(false);
    expect(isStaleChunkError(null)).toBe(false);
  });
});

describe('reloading once for a stale chunk', () => {
  let storage: Storage;
  let clock: number;
  const reload = vi.fn();
  beforeEach(() => { storage = memoryStorage(); clock = Date.UTC(2026, 10, 12, 13, 0); reload.mockReset(); });
  const deps = () => ({ storage, now: () => clock, reload });

  it('reloads the first time and refuses again within the guard window, so a missing chunk cannot loop', async () => {
    let module = await load();
    expect(module.reloadOnceForStaleChunk(deps())).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);

    // The page reloads: a fresh module, the same session storage, a few seconds later.
    module = await load();
    clock += 5_000;
    expect(module.reloadOnceForStaleChunk(deps())).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('reloads again for a later deploy, once the guard window has passed', async () => {
    let module = await load();
    module.reloadOnceForStaleChunk(deps());
    module = await load();
    clock += module.STALE_CHUNK_RELOAD_WINDOW_MS + 1;
    expect(module.reloadOnceForStaleChunk(deps())).toBe(true);
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it('does not reload when it cannot record the attempt', async () => {
    const { reloadOnceForStaleChunk } = await load();
    const blocked = { ...memoryStorage(), getItem: () => { throw new DOMException('blocked', 'SecurityError'); } } as Storage;
    expect(reloadOnceForStaleChunk({ storage: blocked, now: () => clock, reload })).toBe(false);
    expect(reloadOnceForStaleChunk({ storage: null, now: () => clock, reload })).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });
});

describe("Vite's preload error event", () => {
  let uninstall: () => void = () => {};
  afterEach(() => uninstall());

  // Vite dispatches the event with the import's error as its payload.
  function preloadError(payload: unknown): Event {
    const event = new Event('vite:preloadError', { cancelable: true });
    (event as Event & { payload?: unknown }).payload = payload;
    return event;
  }
  const retiredChunk = () => new TypeError('Failed to fetch dynamically imported module: https://x/assets/HomePanel-B1k_VtHD.js');

  it('reloads and swallows the first failure, then lets a repeat reach the error screen', async () => {
    const storage = memoryStorage();
    const reload = vi.fn();
    let { installStaleChunkReload } = await load();
    uninstall = installStaleChunkReload(window, { storage, now: () => 1_000_000, reload });
    const first = preloadError(retiredChunk());
    window.dispatchEvent(first);
    expect(first.defaultPrevented).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
    uninstall();

    ({ installStaleChunkReload } = await load());
    uninstall = installStaleChunkReload(window, { storage, now: () => 1_010_000, reload });
    const repeat = preloadError(retiredChunk());
    window.dispatchEvent(repeat);
    expect(repeat.defaultPrevented).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('leaves a module that threw while it ran to the error screen, without reloading', async () => {
    // Vite raises the same event when a lazily loaded module loads but throws: that is a bug to show, not a stale tab.
    const reload = vi.fn();
    const { installStaleChunkReload } = await load();
    uninstall = installStaleChunkReload(window, { storage: memoryStorage(), now: () => 1_000_000, reload });
    const failed = preloadError(new ReferenceError('foo is not defined'));
    window.dispatchEvent(failed);
    expect(failed.defaultPrevented).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });
});
