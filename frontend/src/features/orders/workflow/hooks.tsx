import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { CheckCircle2, Info } from 'lucide-react';

/** Server time now, ticking every `everyMs`: the browser clock corrected by the offset measured against the server's `now`. */
export function useServerClock(offsetMs: number, everyMs: number, running = true): number {
  const [clock, setClock] = useState(() => ({ offsetMs, now: Date.now() + offsetMs }));
  // A new offset (a list or an order just arrived) applies in the same render: drawing first with the old one would show
  // every deadline against the uncorrected device clock for a frame.
  let current = clock;
  if (clock.offsetMs !== offsetMs) {
    current = { offsetMs, now: Date.now() + offsetMs };
    setClock(current);
  }
  useEffect(() => {
    setClock({ offsetMs, now: Date.now() + offsetMs });
    if (!running) return undefined;
    const timer = window.setInterval(() => setClock({ offsetMs, now: Date.now() + offsetMs }), everyMs);
    return () => window.clearInterval(timer);
  }, [offsetMs, everyMs, running]);
  return current.now;
}

/** Whether a media query matches, following changes; false where the browser cannot tell (tests, server rendering). */
export function useMediaQuery(query: string): boolean {
  const read = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
  const [matches, setMatches] = useState(read);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener?.('change', update);
    return () => list.removeEventListener?.('change', update);
  }, [query]);
  return matches;
}

/** Below the documented desktop breakpoint (1024 px): one column, the stage list as a select (section 4.3). */
export const SINGLE_COLUMN = '(max-width: 1023px)';

/** Below the documented tablet breakpoint (768 px). */
export const NARROW = '(max-width: 767px)';

/**
 * A form's local copy of a saved value. It follows the server only when the saved value itself changes (`key` is its
 * canonical form), so a save or reload that leaves this value alone (a checklist tick, a logged call) keeps what the user
 * has typed or ticked and not saved yet.
 */
export function useServerDraft<T>(saved: T, key: string): [T, Dispatch<SetStateAction<T>>] {
  const [draft, setDraft] = useState<T>(saved);
  const synced = useRef(key);
  useEffect(() => {
    if (synced.current === key) return;
    synced.current = key;
    setDraft(saved);
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps
  return [draft, setDraft];
}

export interface ToastMessage { id: number; text: string; ok: boolean }

/** One toast at a time, replaced by the next and gone after four seconds. */
export function useToast() {
  const [toast, setToast] = useState<ToastMessage | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const show = useCallback((text: string, ok = true) => {
    window.clearTimeout(timer.current);
    setToast({ id: Date.now(), text, ok });
    timer.current = window.setTimeout(() => setToast(null), 4000);
  }, []);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return { toast, show };
}

/** The house toast: a polite status message, so assistive technology reads it without moving focus. */
export function Toast({ toast }: { toast: ToastMessage | null }) {
  return (
    <div className="ck-wf-toast-region" role="status" aria-live="polite">
      {toast && (
        <div key={toast.id} className={`ck-command-toast ${toast.ok ? 'ok' : 'fail'}`}>
          <span className={`ck-command-toast-icon ${toast.ok ? 'ok' : 'fail'}`} aria-hidden="true">
            {toast.ok ? <CheckCircle2 size={17} /> : <Info size={17} />}
          </span>
          <span className="ck-command-toast-text">{toast.text}</span>
        </div>
      )}
    </div>
  );
}
