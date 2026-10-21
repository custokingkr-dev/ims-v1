import { prettyOrderStatus } from '../utils';

/**
 * Spec v4: a notebook order on the tracker no longer goes through pricing, approval or delivery, so an orders list shows
 * the step it is at (its status), not the old stages or an amount.
 */
export function TrackerStep({ status }: { status: string }) {
  return (
    <div>
      <div className="ts">Current step</div>
      <div className="tb">{prettyOrderStatus(status)}</div>
    </div>
  );
}
