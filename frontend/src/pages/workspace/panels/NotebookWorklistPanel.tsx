import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { OrderWorkflowList } from '../../../features/orders/list';
import { asOrderNumber } from '../../../features/orders/shared/orderLabel';
import { OrderDetailEntry } from '../../../features/orders/workflow';
import { isNotFound, workflowApi, workflowError } from '../../../features/orders/workflow/api';

/** A CK id, as every API path takes it; anything else in the URL is ignored. */
const ORDER_ID = /^[A-Za-z0-9_-]{1,255}$/;

/**
 * A link that names the order by its number (NB-DPS_GUR-26-11-13-0042), as people copy it from the screen: the number is
 * looked up and the link continues under the order's CK id, which every workflow call takes.
 */
function OrderNumberLink({ orderNumber, onFound, onBack }: { orderNumber: string; onFound: (orderId: string) => void; onBack: () => void }) {
  const [error, setError] = useState('');
  const report = useRef(onFound);
  report.current = onFound;
  useEffect(() => {
    let active = true;
    setError('');
    workflowApi.orderIdByNumber(orderNumber).then((orderId) => { if (active) report.current(orderId); }, (e: unknown) => {
      if (active) setError(isNotFound(e) ? `No order has the number ${orderNumber}.` : workflowError(e, 'The order could not be found.'));
    });
    return () => { active = false; };
  }, [orderNumber]);
  if (!error) return <p className="ck-wf-muted" role="status">Finding order {orderNumber}…</p>;
  return (
    <div className="ck-wf">
      <div><button type="button" className="ck-btn ck-btn-ghost" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> Order workflows</button></div>
      <div className="ck-alert ck-alert-re" role="alert">{error}</div>
    </div>
  );
}

/**
 * The "Order workflows" panel (key `nb-work`, kept so bookmarks still work): the list and board of every placed workflow
 * order, and one order's workflow at `?panel=nb-work&order=<CK id>` (or the order's number). Opening an order pushes a
 * history entry, so the browser's Back closes it again.
 */
export function NotebookWorklistPanel() {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const requested = params.get('order');
  const orderNumber = requested ? asOrderNumber(requested) : null;
  const orderId = requested && !orderNumber && ORDER_ID.test(requested) ? requested : null;

  const open = useCallback((id: string) => {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.set('order', id);
      return next;
    }, { state: { fromWorkflowList: true } });
  }, [setParams]);

  // The number resolved: the same history entry now names the order by its CK id, keeping where it was opened from.
  const found = useCallback((id: string) => {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.set('order', id);
      return next;
    }, { replace: true, state: location.state });
  }, [location.state, setParams]);

  const close = useCallback(() => {
    // Opened from the list: going back returns to the list exactly as it was. Opened from a link: there is no list entry
    // behind it, so the order is closed in place.
    if ((location.state as { fromWorkflowList?: boolean } | null)?.fromWorkflowList) {
      navigate(-1);
      return;
    }
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.delete('order');
      return next;
    }, { replace: true });
  }, [location.state, navigate, setParams]);

  if (orderNumber) return <OrderNumberLink key={orderNumber} orderNumber={orderNumber} onFound={found} onBack={close} />;
  if (orderId) return <OrderDetailEntry key={orderId} orderId={orderId} trackerManaged onBack={close} backLabel="Order workflows" />;
  return <OrderWorkflowList onOpen={open} />;
}
