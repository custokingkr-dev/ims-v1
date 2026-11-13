import type { AxiosInstance } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import { createCatalogClient } from './catalogClient';
import { createOrderWorkflowClient, type StepView } from './orderWorkflowClient';
import { createTenantSchoolClient } from './tenantSchoolClient';
import { createVendorDirectoryClient } from './vendorDirectoryClient';

function transport() {
  const get = vi.fn(); const post = vi.fn(); const put = vi.fn(); const patch = vi.fn(); const deleteRequest = vi.fn();
  for (const method of [get, post, put, patch, deleteRequest]) method.mockResolvedValue({ data: {} });
  return { get, post, put, patch, deleteRequest, http: { get, post, put, patch, delete: deleteRequest } as unknown as AxiosInstance };
}

// Design section 3.5. The CK id is the key in every path; the order number never is.
describe('order workflow browser contract', () => {
  it('sends every list filter as a query parameter, items as one comma-separated value', async () => {
    const { http, get } = transport();
    const filters = {
      item: 'NB,FE', step: 'PRE_PRINT_QUALITY_GATE', assignee: 'unassigned', schoolId: 4, sla: 'OVERDUE' as const,
      q: 'dps', includeFinished: true, sort: 'number' as const, limit: 50, offset: 100,
    };
    await createOrderWorkflowClient(http).listOrderWorkflows(filters);
    expect(get).toHaveBeenCalledWith('/workflows/item-orders', { params: filters });
    await createOrderWorkflowClient(http).listOrderWorkflows();
    expect(get).toHaveBeenLastCalledWith('/workflows/item-orders', { params: {} });
  });

  it('encodes the order id once and keeps step actions on distinct routes with the reviewed version, zero included', async () => {
    const { http, post, put } = transport();
    const client = createOrderWorkflowClient(http);
    const order = { orderId: 'CK 1015/x', step: 7 };
    await client.saveStepData(order, { data: { checks: [0, 1], sampleLink: 'https://drive.test/s' }, expectedVersion: 0 });
    await client.completeStep(order, { expectedVersion: 0 });
    await client.reopenStep({ orderId: 'CK-1015', step: 2 }, { reason: 'School asked for a new logo', expectedVersion: 3 });
    await client.signOffStep(order, { expectedVersion: 4 });
    expect(put.mock.calls).toEqual([
      ['/workflows/item-orders/CK%201015%2Fx/steps/7/data', { data: { checks: [0, 1], sampleLink: 'https://drive.test/s' }, expectedVersion: 0 }],
    ]);
    expect(post.mock.calls).toEqual([
      ['/workflows/item-orders/CK%201015%2Fx/steps/7/complete', { expectedVersion: 0 }],
      ['/workflows/item-orders/CK-1015/steps/2/reopen', { reason: 'School asked for a new logo', expectedVersion: 3 }],
      ['/workflows/item-orders/CK%201015%2Fx/steps/7/sign-off', { expectedVersion: 4 }],
    ]);
  });

  it('surfaces GATE_UNMET with its unmet list, without retrying or reloading', async () => {
    const { http, post, get } = transport();
    const refusal = { response: { status: 409, data: { code: 'GATE_UNMET', message: 'Complete the stage first', unmet: [{ code: 'FIELDS_PRESENT', message: 'Save the print sample evidence link' }] } } };
    post.mockRejectedValueOnce(refusal);
    await expect(createOrderWorkflowClient(http).completeStep({ orderId: 'CK-1015', step: 7 }, { expectedVersion: 2 })).rejects.toBe(refusal);
    expect(post).toHaveBeenCalledTimes(1);
    expect(get).not.toHaveBeenCalled();
  });

  it('keeps approve and request-changes apart, each with its required comment', async () => {
    const { http, post } = transport();
    const client = createOrderWorkflowClient(http);
    await client.approveDesign({ orderId: 'CK-1015' }, { comment: 'Matches the school brief', expectedVersion: 1 });
    await client.requestDesignChanges({ orderId: 'CK-1015' }, { comment: 'Logo is blurred', expectedVersion: 1 });
    expect(post.mock.calls.map(([url]) => url)).toEqual([
      '/workflows/item-orders/CK-1015/head-approval/approve', '/workflows/item-orders/CK-1015/head-approval/request-changes',
    ]);
  });

  it('claims an unassigned order with no body, and assigns through superadmin routes', async () => {
    const { http, post, put } = transport();
    const client = createOrderWorkflowClient(http);
    await client.claimOrder({ orderId: 'CK-1015' });
    expect(post.mock.calls).toEqual([['/workflows/item-orders/CK-1015/claim']]);
    await client.assignOrder({ orderId: 'CK-1015' }, { userId: 12 });
    await client.assignStep({ orderId: 'CK-1015', step: 5 }, { userId: 13 });
    expect(put.mock.calls).toEqual([
      ['/workflows/item-orders/CK-1015/assignee', { userId: 12 }],
      ['/workflows/item-orders/CK-1015/steps/5/assignee', { userId: 13 }],
    ]);
  });

  it('saves vendor selections, designer, contacts and the item-level school group on their own routes', async () => {
    const { http, put } = transport();
    const client = createOrderWorkflowClient(http);
    await client.setVendorSelections({ orderId: 'CK-1015' }, { selections: { OFFSET_PRINTERS: [3, 4] }, expectedVersion: 9 });
    await client.setDesigner({ orderId: 'CK-1015' }, { designerId: null });
    const contacts = { schoolContactName: 'Asha', schoolContactPhone: '+919800000000', factoryContactName: null, factoryContactPhone: null };
    await client.setContacts({ orderId: 'CK-1015' }, contacts);
    await client.setOrderSchoolGroup({ orderId: 'CK-1015' }, { inviteUrl: 'https://chat.whatsapp.com/AbCdEf12345' });
    expect(put.mock.calls).toEqual([
      ['/workflows/item-orders/CK-1015/vendor-selections', { selections: { OFFSET_PRINTERS: [3, 4] }, expectedVersion: 9 }],
      ['/workflows/item-orders/CK-1015/designer', { designerId: null }],
      ['/workflows/item-orders/CK-1015/contacts', contacts],
      ['/workflows/item-orders/CK-1015/whatsapp-groups/school', { inviteUrl: 'https://chat.whatsapp.com/AbCdEf12345' }],
    ]);
  });

  it('appends notes and calls, and pages the activity timeline by its cursor', async () => {
    const { http, get, post } = transport();
    const client = createOrderWorkflowClient(http);
    await client.addNote({ orderId: 'CK-1015' }, { body: 'Printer confirmed plates for Monday', stepOrder: 8 });
    const call = { contacted: 'PRINTING_PRESS' as const, outcome: 'CALLBACK_REQUESTED' as const, vendorId: 3, notes: 'Call back after lunch', nextFollowUpAt: '2026-11-14T08:30:00Z' };
    await client.logCall({ orderId: 'CK-1015' }, call);
    expect(post.mock.calls).toEqual([
      ['/workflows/item-orders/CK-1015/notes', { body: 'Printer confirmed plates for Monday', stepOrder: 8 }],
      ['/workflows/item-orders/CK-1015/calls', call],
    ]);
    await client.listCalls({ orderId: 'CK-1015', limit: 10 });
    await client.getOrderActivity({ orderId: 'CK-1015', before: '2026-11-13T06:00:00Z', limit: 50, step: 8 });
    expect(get.mock.calls).toEqual([
      ['/workflows/item-orders/CK-1015/calls', { params: { limit: 10 } }],
      ['/workflows/item-orders/CK-1015/activity', { params: { before: '2026-11-13T06:00:00Z', limit: 50, step: 8 } }],
    ]);
  });

  it('edits template defaults and per-school time slots by template', async () => {
    const { http, get, put, deleteRequest } = transport();
    const client = createOrderWorkflowClient(http);
    await client.setTemplateStepSla({ definitionId: 'FLIER_ORDER_WORKFLOW', step: 6 }, { value: 24, unit: 'HOURS' });
    await client.listTimeSlots({ schoolId: 4, definitionId: 'FLIER_ORDER_WORKFLOW' });
    await client.setTimeSlot({ schoolId: 4, definitionId: 'FLIER_ORDER_WORKFLOW', step: 6 }, { value: null, unit: null });
    await client.clearTimeSlot({ schoolId: 4, definitionId: 'FLIER_ORDER_WORKFLOW', step: 6 });
    expect(put.mock.calls).toEqual([
      ['/workflows/item-orders/templates/FLIER_ORDER_WORKFLOW/steps/6/sla', { value: 24, unit: 'HOURS' }],
      ['/workflows/time-slots/4/FLIER_ORDER_WORKFLOW/6', { value: null, unit: null }],
    ]);
    expect(get).toHaveBeenCalledWith('/workflows/time-slots/4', { params: { definitionId: 'FLIER_ORDER_WORKFLOW' } });
    expect(deleteRequest).toHaveBeenCalledWith('/workflows/time-slots/4/FLIER_ORDER_WORKFLOW/6');
  });

  it('types each step with its gate list, version and capabilities', () => {
    const step: Pick<StepView, 'unmet' | 'version' | 'can' | 'status'> = {
      status: 'ACTIVE',
      unmet: [{ code: 'SIGNED_OFF', message: 'Record the reviewer sign-off' }],
      version: 0,
      can: { canEdit: true, canComplete: false, canReopen: false, canSignOff: false, canAddNote: true },
    };
    expect(step.unmet).toHaveLength(1);
  });
});

describe('vendor directory browser contract', () => {
  it('filters vendors in the query and writes categories and vendors on their own routes', async () => {
    const { http, get, post, put } = transport();
    const client = createVendorDirectoryClient(http);
    await client.listVendors({ category: 'FLEX_PRINTERS', q: 'print', includeInactive: true });
    expect(get).toHaveBeenCalledWith('/workflows/vendors', { params: { category: 'FLEX_PRINTERS', q: 'print', includeInactive: true } });
    const vendor = { name: 'Shree Flex', categories: ['FLEX_PRINTERS'], phone: '+919800000001', whatsappGroupUrl: 'https://chat.whatsapp.com/AbCdEf12345' };
    await client.createVendor(vendor);
    await client.updateVendor({ id: 7 }, { ...vendor, active: false });
    await client.updateVendorCategory({ code: 'BATCH_MAKERS' }, { name: 'Badge Makers' });
    expect(post).toHaveBeenCalledWith('/workflows/vendors', vendor);
    expect(put.mock.calls).toEqual([
      ['/workflows/vendors/7', { ...vendor, active: false }],
      ['/workflows/vendor-categories/BATCH_MAKERS', { name: 'Badge Makers' }],
    ]);
  });
});

describe('order numbers and codes in school-core contracts', () => {
  it('searches orders on the server and resolves a pasted order number to its CK id', async () => {
    const { http, get } = transport();
    const catalog = createCatalogClient(http);
    await catalog.getOrderPage({ q: 'NB-DPS', page: 0, size: 50 });
    expect(get).toHaveBeenCalledWith('/catalog/orders/page', { params: { q: 'NB-DPS', page: 0, size: 50 } });
    get.mockResolvedValueOnce({ data: { id: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042' } });
    await expect(catalog.getOrderByNumber({ orderNumber: 'NB-DPS_GUR-26-11-13-0042' }))
      .resolves.toEqual({ id: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042' });
    expect(get).toHaveBeenLastCalledWith('/catalog/orders/by-number/NB-DPS_GUR-26-11-13-0042');
    await catalog.getOrderWorkflowSummary({ id: 'CK-1015' });
    expect(get).toHaveBeenLastCalledWith('/catalog/orders/CK-1015/tracker-summary');
  });

  it('sends a school order code on onboarding and on edit', async () => {
    const { http, post, patch } = transport();
    const tenant = createTenantSchoolClient(http);
    await tenant.createSchool({ name: 'Delhi Public School', shortCode: 'DPSG', city: 'Gurugram', orderCode: null });
    await tenant.updateSchool({ id: 4 }, { orderCode: 'DPS2_GUR' });
    expect(post).toHaveBeenCalledWith('/schools', { name: 'Delhi Public School', shortCode: 'DPSG', city: 'Gurugram', orderCode: null });
    expect(patch).toHaveBeenCalledWith('/schools/4', { orderCode: 'DPS2_GUR' });
  });
});
