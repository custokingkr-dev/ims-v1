import { useEffect, useState } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import { ProductOrderDetail } from '../catalog/ProductOrderDetail';
import { NotebookTracker } from './NotebookTracker';
import { trackerApi } from './api';
import { errorMessage } from '../catalog/api';
import type { TrackerView } from './types';

/**
 * The order screen for staff: a notebook order that has a tracker opens as the tracker, anything else (other categories, or a
 * notebook whose tracker is not created yet) keeps the ordinary order detail. Only superadmin and operators ask for a tracker.
 */
export function OrderDetailEntry({ orderId, onBack, onChanged }: { orderId: string; onBack: () => void; onChanged?: () => void }) {
  const { user } = useAuth();
  const staff = user?.role === 'SUPERADMIN' || user?.role === 'OPERATIONS';
  const [state, setState] = useState<{ orderId: string; tracker: TrackerView | null; pending?: boolean } | 'loading'>('loading');
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState('');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    setState('loading');
    if (!staff) { setState({ orderId, tracker: null }); return () => { active = false; }; }
    trackerApi.get(orderId).then((tracker) => { if (active) setState({ orderId, tracker }); })
      .catch(async () => {
        // No tracker: either this is not a tracker order, or placement could not create it. Only the second can be started.
        const pending = await trackerApi.summary(orderId).then((s) => s.trackerPending, () => false);
        if (active) setState({ orderId, tracker: null, pending });
      });
    return () => { active = false; };
  }, [orderId, staff, attempt]);

  const start = async () => {
    setStarting(true); setStartError('');
    try {
      const result = await trackerApi.startTracker(orderId);
      if (result.trackerPending) setStartError('The tracker service could not be reached. Try again shortly.');
      else setAttempt((n) => n + 1);
    } catch (e) { setStartError(errorMessage(e, 'Unable to start the tracker.')); } finally { setStarting(false); }
  };

  if (state === 'loading') return <p className="ck-product-muted" role="status">Loading order…</p>;
  if (state.tracker) return <NotebookTracker orderId={orderId} initial={state.tracker} onBack={onBack} onChanged={onChanged} />;
  return <>
    {state.pending && (
      <div className="ck-alert ck-alert-re" role="alert">
        The tracker for this order was not started when it was placed.{' '}
        <button type="button" className="ck-btn ck-btn-g" disabled={starting} onClick={() => void start()}>Start tracker</button>
        {startError && <div>{startError}</div>}
      </div>
    )}
    <ProductOrderDetail orderId={orderId} onBack={onBack} onChanged={onChanged} />
  </>;
}
