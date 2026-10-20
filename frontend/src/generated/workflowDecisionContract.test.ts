import { describe, expect, it, vi } from 'vitest';
import type { AxiosInstance } from 'axios';
import { createWorkflowClient } from './workflowClient';
import type { CreateBillingPaymentRequest } from './billingClient';
describe('workflow decision and billing payment contracts', () => {
  it('preserves reviewed zero version for approve/reject', async () => {
    const post = vi.fn().mockResolvedValue({ data: { id: 3, version: 1 } });
    const client = createWorkflowClient({ post } as unknown as AxiosInstance);
    const request = { expectedVersion: 0, notes: 'Reviewed quotation' };
    await client.approveWorkflowInstance({ id: 3 }, request);
    await client.rejectWorkflowInstance({ id: 3 }, request);
    expect(post.mock.calls).toEqual([['/workflows/instances/3/approve', request], ['/workflows/instances/3/reject', request]]);
  });
  it('propagates stale decisions without automatic retry or reload', async () => {
    const conflict = { response: { status: 409 } };
    const post = vi.fn().mockRejectedValue(conflict); const get = vi.fn();
    const client = createWorkflowClient({ post, get } as unknown as AxiosInstance);
    await expect(client.approveWorkflowInstance({ id: 3 }, { expectedVersion: 7 })).rejects.toBe(conflict);
    expect(post).toHaveBeenCalledTimes(1); expect(get).not.toHaveBeenCalled();
  });
  it('exports a required billing key without actor/branch fields', () => {
    const request: CreateBillingPaymentRequest = { invoiceId: 4, amount: 100, paymentMode: 'UPI', idempotencyKey: 'original-payment-key' };
    expect(Object.keys(request)).toEqual(['invoiceId', 'amount', 'paymentMode', 'idempotencyKey']);
  });
});
