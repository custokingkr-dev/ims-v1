import api from '../../../services/api';
import { createOrderWorkflowClient, type CallLogEntry, type CallLogRequest } from '../../../generated/orderWorkflowClient';
import { NOT_UNDERSTOOD, parseList } from '../shared/errors';

export type { CallLogEntry, CallLogRequest };

const workflows = createOrderWorkflowClient(api);

/** An order's call log (design D35): append-only, newest first. */
export const callsApi = {
  list: async (orderId: string, limit: number): Promise<CallLogEntry[]> => parseList<CallLogEntry>(await workflows.listCalls({ orderId, limit })),
  log: async (orderId: string, request: CallLogRequest): Promise<CallLogEntry> => {
    const entry: unknown = await workflows.logCall({ orderId }, request);
    if (typeof entry !== 'object' || entry === null || Array.isArray(entry)) throw new Error(NOT_UNDERSTOOD);
    return entry as CallLogEntry;
  },
};
