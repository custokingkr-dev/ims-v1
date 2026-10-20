import { useEffect, useState } from 'react';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { useAuth } from '../../contexts/AuthContext';
import { ProductOrderDetail } from '../catalog/ProductOrderDetail';
import { NotebookTracker } from './NotebookTracker';
import { trackerApi } from './api';
import { errorMessage } from '../catalog/api';
import type { TrackerView } from './types';
import '../catalog/product-form.css';
import './tracker.css';

type EntryState =
  | { kind: 'loading' }
  | { kind: 'tracker'; tracker: TrackerView }
  /** Placed on the tracker, but creating the tracker is still being retried. */
  | { kind: 'pending' }
  | { kind: 'error'; message: string };

const httpStatus = (error: unknown) => (error as { response?: { status?: number } })?.response?.status;
const failure = (error: unknown): EntryState => ({ kind: 'error', message: errorMessage(error, 'The tracker for this order could not be loaded.') });

/**
 * The order screen for staff. A notebook order placed on the tracker (`trackerManaged`) opens as the tracker; every other
 * order, including a notebook order placed before the tracker (owner decision D1), keeps the ordinary order detail and the
 * tracker is never asked about it. Only superadmin and operators see the tracker.
 */
export function OrderDetailEntry({ orderId, trackerManaged, onBack, onChanged }: {
  orderId: string; trackerManaged: boolean; onBack: () => void; onChanged?: () => void;
}) {
  const { user } = useAuth();
  const staff = user?.role === 'SUPERADMIN' || user?.role === 'OPERATIONS';
  // The caller's list row can be stale: an order placed after the list loaded, or placed from the order screen below. The
  // order screen reports what the server says, and the tracker opens then.
  const [foundOnTracker, setFoundOnTracker] = useState<string | null>(null);
  const onTracker = staff && (trackerManaged || foundOnTracker === orderId);
  const [state, setState] = useState<EntryState>({ kind: 'loading' });
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState('');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!onTracker) return undefined;
    let active = true;
    setState({ kind: 'loading' });
    trackerApi.get(orderId).then(
      (tracker) => { if (active) setState({ kind: 'tracker', tracker }); },
      async (error: unknown) => {
        // Not found is expected only while the tracker is still being created after placement; anything else is a failure to show.
        const next = httpStatus(error) === 404
          ? await trackerApi.summary(orderId).then((s): EntryState => (s.trackerPending ? { kind: 'pending' } : failure(error)), failure)
          : failure(error);
        if (active) setState(next);
      });
    return () => { active = false; };
  }, [orderId, onTracker, attempt]);

  const start = async () => {
    setStarting(true); setStartError('');
    try {
      const result = await trackerApi.startTracker(orderId);
      if (result.trackerPending) setStartError('The tracker service could not be reached. Try again shortly.');
      else setAttempt((n) => n + 1);
    } catch (e) { setStartError(errorMessage(e, 'Unable to start the tracker.')); } finally { setStarting(false); }
  };

  if (!onTracker) {
    return <ProductOrderDetail orderId={orderId} onBack={onBack} onChanged={onChanged} onTrackerManaged={staff ? () => setFoundOnTracker(orderId) : undefined} />;
  }
  if (state.kind === 'loading') return <p className="ck-product-muted" role="status">Loading order…</p>;
  if (state.kind === 'tracker') return <NotebookTracker orderId={orderId} initial={state.tracker} onBack={onBack} onChanged={onChanged} />;
  if (state.kind === 'error') {
    return (
      <div className="ck-trk">
        <div><button type="button" className="ck-btn ck-btn-ghost" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> Back to orders</button></div>
        <div className="ck-alert ck-alert-re" role="alert">{state.message}</div>
        <div><button type="button" className="ck-btn ck-btn-ghost" onClick={() => setAttempt((n) => n + 1)}><RefreshCw size={15} aria-hidden="true" /> Retry</button></div>
      </div>
    );
  }
  return <>
    <div className="ck-alert ck-alert-am" role="status">
      <div>
        The tracker for this order is still being set up; it is retried automatically.{' '}
        <button type="button" className="ck-btn ck-btn-g" disabled={starting} onClick={() => void start()}>Start tracker</button>
        {startError && <div role="alert">{startError}</div>}
      </div>
    </div>
    <ProductOrderDetail orderId={orderId} onBack={onBack} onChanged={onChanged} />
  </>;
}
