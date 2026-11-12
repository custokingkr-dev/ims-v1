/**
 * A tab opened before a deploy still runs the old release, whose content-hashed chunks the new release no longer
 * serves. The first time such a tab opens a panel it has not loaded yet, the chunk request 404s and the whole app
 * would fall into the error screen. Reloading fetches the current release, so the fix is to reload, once.
 *
 * "Once" is enforced across the reload with a timestamp in sessionStorage: a second chunk failure within
 * STALE_CHUNK_RELOAD_WINDOW_MS of the last reload is not a stale tab but a chunk that is really missing, and
 * reloading again would loop. A later deploy, after the window, may reload the same tab again.
 */
const RELOAD_KEY = 'ck:stale-chunk-reload-at';
export const STALE_CHUNK_RELOAD_WINDOW_MS = 60_000;
/**
 * How long the page waits for its reload to take it away. A reload can be refused (Stay on the browser's "Leave site?"
 * prompt for an unsaved draft), and a page still here after this long offers the error screen and its reload button.
 */
export const STALE_CHUNK_RELOAD_STALL_MS = 5_000;

// Chrome, Firefox and Safari word a failed dynamic import differently; Vite words a failed CSS preload.
const STALE_CHUNK_MESSAGE = /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS/i;

let reloadPending = false;

export function isStaleChunkError(error: unknown): boolean {
  const message = (error as { message?: unknown } | null)?.message;
  return typeof message === 'string' && STALE_CHUNK_MESSAGE.test(message);
}

/** True once this page has started reloading onto the new release. */
export function isStaleChunkReloadPending(): boolean {
  return reloadPending;
}

interface ReloadDependencies {
  storage?: Storage | null;
  now?: () => number;
  reload?: () => void;
}

function sessionStore(): Storage | null {
  try { return window.sessionStorage; } catch { return null; }
}

/** Reloads the page unless it already did so recently. Returns whether a reload is under way. */
export function reloadOnceForStaleChunk({ storage = sessionStore(), now = Date.now, reload = () => window.location.reload() }: ReloadDependencies = {}): boolean {
  if (reloadPending) return true;
  // Without somewhere to record the attempt there is no loop guard, so leave the error screen and its reload button.
  if (!storage) return false;
  try {
    const at = now();
    const last = Number(storage.getItem(RELOAD_KEY));
    if (last > 0 && Math.abs(at - last) < STALE_CHUNK_RELOAD_WINDOW_MS) return false;
    storage.setItem(RELOAD_KEY, String(at));
  } catch {
    return false;
  }
  reloadPending = true;
  reload();
  return true;
}

/**
 * Vite raises `vite:preloadError` when a lazily imported chunk or its CSS fails to load, including imports made
 * outside React. Cancelling it stops the error from being thrown while the page reloads; when the guard refuses,
 * the error is left to reach the error screen. Vite raises it too for a module that loaded but threw while it ran,
 * which is a bug and not a stale tab, so only a chunk that failed to load reloads the page.
 */
export function installStaleChunkReload(target: Window = window, dependencies?: ReloadDependencies): () => void {
  const onPreloadError = (event: Event) => {
    const error = (event as Event & { payload?: unknown }).payload;
    if (isStaleChunkError(error) && reloadOnceForStaleChunk(dependencies)) event.preventDefault();
  };
  target.addEventListener('vite:preloadError', onPreloadError);
  return () => target.removeEventListener('vite:preloadError', onPreloadError);
}
