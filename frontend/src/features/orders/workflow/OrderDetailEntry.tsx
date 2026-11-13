import { useEffect, useState } from 'react';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { useAuth } from '../../../contexts/AuthContext';
import { ProductOrderDetail } from '../../catalog/ProductOrderDetail';
import { isNotFound, workflowApi, workflowError } from './api';
import { OrderWorkflowPage } from './OrderWorkflowPage';
import type { OrderWorkflowView } from './types';
import '../../catalog/product-form.css';
import './workflow.css';

type EntryState =
  | { kind: 'loading' }
  | { kind: 'workflow'; view: OrderWorkflowView }
  /** Placed as a workflow order, but creating its workflow is still being retried. */
  | { kind: 'pending' }
  | { kind: 'error'; message: string };

const failure = (error: unknown): EntryState => ({ kind: 'error', message: workflowError(error, 'The workflow for this order could not be loaded.') });

/**
 * The order screen for staff. An order of any workflow category (`trackerManaged`) opens as its order workflow; every other
 * order keeps the ordinary order detail and the workflow service is never asked about it. Only superadmin and operators see
 * workflows; school users always get the order detail.
 */
export function OrderDetailEntry({ orderId, trackerManaged, onBack, onChanged, backLabel = 'Back to orders' }: {
  orderId: string; trackerManaged: boolean; onBack: () => void; onChanged?: () => void;
  /** The back button's words, naming the list the order was opened from. */
  backLabel?: string;
}) {
  const { user } = useAuth();
  const staff = user?.role === 'SUPERADMIN' || user?.role === 'OPERATIONS';
  // The caller's list row can be stale: an order placed after the list loaded, or placed from the order screen below. The
  // order screen reports what the server says, and the workflow opens then.
  const [found, setFound] = useState<string | null>(null);
  const onWorkflow = staff && (trackerManaged || found === orderId);
  const [state, setState] = useState<EntryState>({ kind: 'loading' });
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState('');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!onWorkflow) return undefined;
    let active = true;
    setState({ kind: 'loading' });
    workflowApi.view(orderId).then(
      (view) => { if (active) setState({ kind: 'workflow', view }); },
      async (error: unknown) => {
        // Not found is expected only while the workflow is still being created after placement; anything else is a failure.
        const next = isNotFound(error)
          ? await workflowApi.summary(orderId).then((summary): EntryState => (summary.trackerPending ? { kind: 'pending' } : failure(error)), failure)
          : failure(error);
        if (active) setState(next);
      });
    return () => { active = false; };
  }, [orderId, onWorkflow, attempt]);

  const start = async () => {
    setStarting(true); setStartError('');
    try {
      const result = await workflowApi.ensure(orderId);
      if (result.trackerPending) setStartError('The workflow service could not be reached. Try again shortly.');
      else setAttempt((value) => value + 1);
    } catch (e) { setStartError(workflowError(e, 'The workflow could not be started.')); } finally { setStarting(false); }
  };

  if (!onWorkflow) {
    return <ProductOrderDetail orderId={orderId} onBack={onBack} onChanged={onChanged} onTrackerManaged={staff ? () => setFound(orderId) : undefined} />;
  }
  if (state.kind === 'loading') return <p className="ck-wf-muted" role="status">Loading order…</p>;
  if (state.kind === 'workflow') return <OrderWorkflowPage orderId={orderId} initial={state.view} onBack={onBack} onChanged={onChanged} backLabel={backLabel} />;
  if (state.kind === 'error') {
    return (
      <div className="ck-wf">
        <div><button type="button" className="ck-btn ck-btn-ghost" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> {backLabel}</button></div>
        <div className="ck-alert ck-alert-re" role="alert">{state.message}</div>
        <div><button type="button" className="ck-btn ck-btn-ghost" onClick={() => setAttempt((value) => value + 1)}><RefreshCw size={15} aria-hidden="true" /> Retry</button></div>
      </div>
    );
  }
  return <>
    <div className="ck-alert ck-alert-am" role="status">
      <div>
        This order&apos;s workflow is still being set up; it is retried automatically.{' '}
        <button type="button" className="ck-btn ck-btn-g" disabled={starting} onClick={() => void start()}>Start workflow</button>
        {startError && <div role="alert">{startError}</div>}
      </div>
    </div>
    <ProductOrderDetail orderId={orderId} onBack={onBack} onChanged={onChanged} />
  </>;
}
