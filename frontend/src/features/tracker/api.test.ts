import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { TRACKER_UNAVAILABLE, parseTracker, trackerApi, trackerError } from './api';

vi.mock('../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }));

const last = (fn: unknown) => { const calls = vi.mocked(fn as typeof api.post).mock.calls; return calls[calls.length - 1] as unknown[]; };

const view = { orderId: 'CK 1/x', steps: [{ step: 1, data: null }], progress: {}, design: {}, internalNotes: null, followUps: null };
beforeEach(() => {
  vi.clearAllMocks();
  for (const method of ['get', 'post', 'put', 'delete'] as const) vi.mocked(api[method]).mockResolvedValue({ data: view });
});

// These run the real client against a recording transport: the wire is what the server's records bind to.
describe('tracker wire contract', () => {
  it('encodes the order id into every path and sends the documented body', async () => {
    await trackerApi.saveDraft('CK 1/x', 5, { paperGsm: '300' }, 3);
    expect(api.put).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK%201%2Fx/steps/5/draft', { data: { paperGsm: '300' }, expectedVersion: 3 });
    await trackerApi.saveStageNote('CK 1/x', 5, 'on track', 4);
    expect(api.put).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK%201%2Fx/steps/5/stage-note', { note: 'on track', expectedVersion: 4 });
    await trackerApi.complete('CK 1/x', 5, 3);
    expect(api.post).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK%201%2Fx/steps/5/complete', { expectedVersion: 3 });
    await trackerApi.reopen('CK 1/x', 5);
    expect(last(api.post)[0]).toBe('/workflows/notebook-orders/CK%201%2Fx/steps/5/reopen');
    await trackerApi.signOff('CK 1/x', 8, 6);
    expect(api.post).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK%201%2Fx/steps/8/sign-off', { expectedVersion: 6 });
  });

  it('sends assignment, design, vendor, designer and note requests as the server records expect them', async () => {
    await trackerApi.assignStep('CK-1', 6, 9);
    expect(api.put).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK-1/steps/6/assignee', { userId: 9 });
    await trackerApi.assignOrder('CK-1', 9);
    expect(api.put).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK-1/assignee', { userId: 9 });
    await trackerApi.sendDesign('CK-1');
    expect(last(api.post)[0]).toBe('/workflows/notebook-orders/CK-1/design/send');
    await trackerApi.approveDesign('CK-1', 12);
    expect(api.post).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK-1/design/approve', { draftRef: 12, comment: null });
    await trackerApi.requestChanges('CK-1');
    expect(api.post).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK-1/design/request-changes', { comment: null });
    await trackerApi.setVendor('CK-1', 'Vendor', '999');
    expect(api.put).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK-1/vendor', { name: 'Vendor', phone: '999' });
    await trackerApi.setDesigner('CK-1', 3);
    expect(api.put).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK-1/designer', { designerId: 3 });
    await trackerApi.addInternalNote('CK-1', 'called');
    expect(api.post).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK-1/internal-notes', { body: 'called' });
    await trackerApi.addFollowUp('CK-1', { percentComplete: 40 });
    expect(api.post).toHaveBeenLastCalledWith('/workflows/notebook-orders/CK-1/follow-ups', { percentComplete: 40, nextFollowUpOn: null, contactPhone: null });
  });

  it('asks for the worklist with only the filters given, and refuses a non-list as not understood', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { not: 'a list' } });
    await expect(trackerApi.worklist({ scope: 'mine' })).rejects.toThrow('The response was not understood.');
    expect(api.get).toHaveBeenLastCalledWith('/workflows/notebook-orders', { params: { scope: 'mine', schoolId: undefined, includeFinished: undefined, limit: undefined } });
    vi.mocked(api.get).mockResolvedValue({ data: [] });
    await trackerApi.worklist({ scope: 'all', schoolId: 4, includeFinished: true });
    expect(api.get).toHaveBeenLastCalledWith('/workflows/notebook-orders', { params: { scope: 'all', schoolId: 4, includeFinished: true, limit: undefined } });
  });

  it('sends the whole designer on an update, because the server requires name and phone every time', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { id: 1, name: 'P', phone: '9', active: false } });
    await trackerApi.updateDesigner(1, { name: 'P', phone: '9', active: false });
    expect(api.put).toHaveBeenLastCalledWith('/workflows/notebook-orders/designers/1', { name: 'P', phone: '9', active: false });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 2 } });
    await trackerApi.createDesigner('N', '1');
    expect(api.post).toHaveBeenLastCalledWith('/workflows/notebook-orders/designers', { name: 'N', phone: '1', active: null });
  });

  it('addresses time slots by school and step', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: undefined }); vi.mocked(api.delete).mockResolvedValue({ data: undefined });
    await trackerApi.setTimeSlot(7, 5, 6, 'HOURS');
    expect(api.put).toHaveBeenLastCalledWith('/workflows/notebook-orders/time-slots/7/5', { value: 6, unit: 'HOURS' });
    await trackerApi.clearTimeSlot(7, 5);
    expect(last(api.delete)[0]).toBe('/workflows/notebook-orders/time-slots/7/5');
  });

  it('reaches school-core for drafts, the summary and starting a tracker, through the generated catalog client', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [{ id: 1, assetKind: 'DESIGN_DRAFT' }, { id: 2, assetKind: 'DESIGN_DRAFT', supersededAt: 'x' }, { id: 3, assetKind: 'DESIGN' }] });
    expect((await trackerApi.draftImages('CK 1')).map((d) => d.id)).toEqual([1]);
    expect(api.get).toHaveBeenLastCalledWith('/catalog/orders/CK%201/assets');
    vi.mocked(api.get).mockResolvedValue({ data: { lines: [] } });
    await trackerApi.summary('CK-1');
    expect(api.get).toHaveBeenLastCalledWith('/catalog/orders/CK-1/tracker-summary');
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    await expect(trackerApi.summary('CK-1')).rejects.toThrow('The response was not understood.');
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    await expect(trackerApi.draftImages('CK-1')).rejects.toThrow('The response was not understood.');
    await trackerApi.startTracker('CK-1');
    expect(api.post).toHaveBeenLastCalledWith('/catalog/orders/CK-1/tracker/ensure');
    const file = new File(['x'], 'v1.png', { type: 'image/png' });
    await trackerApi.uploadDraft('CK-1', file);
    expect(last(api.post)[0]).toBe('/catalog/orders/CK-1/assets');
    const form = last(api.post)[1] as FormData;
    expect(form.get('assetKind')).toBe('DESIGN_DRAFT');
    expect((form.get('file') as File).name).toBe('v1.png');
    vi.mocked(api.delete).mockResolvedValue({ data: undefined });
    await trackerApi.deleteDraft('CK-1', 9);
    expect(api.delete).toHaveBeenLastCalledWith('/catalog/orders/CK-1/assets/9');
    const blob = new Blob(['x']);
    vi.mocked(api.get).mockResolvedValue({ data: blob });
    expect(await trackerApi.draftBlob('CK-1', 9)).toBe(blob);
    expect(api.get).toHaveBeenLastCalledWith('/catalog/orders/CK-1/assets/9/content', { responseType: 'blob' });
  });

  it('reaches identity for operator scopes, provisioning, revoking and the operator list, through the generated operator client', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { userId: 5 } });
    await trackerApi.createScopedOperator(7, { fullName: 'N', email: 'n@x.test', temporaryPassword: 'Temp#12345' }, 'TROPHIES');
    expect(api.post).toHaveBeenLastCalledWith('/users/provisioning/schools/7/users/OPERATIONS',
      { fullName: 'N', email: 'n@x.test', temporaryPassword: 'Temp#12345', catalogCategory: 'TROPHIES' });
    await trackerApi.scopeOperator(12, 7, 'NOTEBOOKS');
    expect(api.post).toHaveBeenLastCalledWith('/rbac/users/12/operator-scopes', { schoolId: 7, catalogCategory: 'NOTEBOOKS' });
    vi.mocked(api.delete).mockResolvedValue({ data: undefined });
    await trackerApi.revokeAssignment(12, 34);
    expect(api.delete).toHaveBeenLastCalledWith('/rbac/users/12/roles/34');
    vi.mocked(api.get).mockResolvedValue({ data: [{ assignmentId: 1 }] });
    expect(await trackerApi.operators()).toEqual([{ assignmentId: 1 }]);
    expect(api.get).toHaveBeenLastCalledWith('/rbac/operators', { params: { schoolId: undefined, catalogCategory: undefined } });
  });

  it('lists schools for the superadmin pickers and refuses a malformed answer', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [{ id: 4, name: 'S' }] });
    expect(await trackerApi.schools()).toEqual([{ id: 4, name: 'S' }]);
    expect(api.get).toHaveBeenLastCalledWith('/sa/schools');
    vi.mocked(api.get).mockResolvedValue({ data: 'nope' });
    await expect(trackerApi.schools()).rejects.toThrow('The response was not understood.');
  });

  it('offers only active catalogue items as operator scopes, and refuses an unreadable catalogue', async () => {
    const category = (code: string, active: boolean) => ({ code, label: code.toLowerCase(), emoji: '', description: '', orderType: 'X', formEnabled: true, sortOrder: 1, active });
    vi.mocked(api.get).mockResolvedValue({ data: [category('NOTEBOOKS', true), category('OLD', false)] });
    expect(await trackerApi.categories()).toEqual([{ code: 'NOTEBOOKS', label: 'notebooks' }]);
    expect(api.get).toHaveBeenLastCalledWith('/supply/product-catalog/categories');
    vi.mocked(api.get).mockResolvedValue({ data: [{ code: 1 }] });
    await expect(trackerApi.categories()).rejects.toThrow();
  });

  it('lists operators for the picker and refuses a malformed directory', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [{ id: 5, fullName: 'Op', email: 'o@x.test' }, { id: 0 }] });
    expect(await trackerApi.operatorUsers()).toEqual([{ userId: 5, fullName: 'Op', email: 'o@x.test' }]);
    expect(api.get).toHaveBeenLastCalledWith('/users', { params: { role: 'OPERATIONS', branchId: undefined, zoneId: undefined, active: true, limit: 500 } });
    vi.mocked(api.get).mockResolvedValue({ data: 'nope' });
    await expect(trackerApi.operatorUsers()).rejects.toThrow('The response was not understood.');
  });

  it('normalises missing parts of a tracker and refuses a response that is not one', async () => {
    const parsed = await trackerApi.get('CK-1');
    expect(parsed.steps[0].data).toEqual({});
    expect(parsed.internalNotes).toEqual([]);
    expect(parsed.followUps).toEqual([]);
    expect(() => parseTracker({ orderId: 'x', steps: 'no' })).toThrow();
  });
});

describe('what a failed tracker call tells the user', () => {
  it('says plainly that the tracker is unavailable for a failing service or network, and keeps a reason the server gave', () => {
    expect(trackerError({ response: { status: 502, data: { message: 'Gateway upstream error' } } })).toBe(TRACKER_UNAVAILABLE);
    expect(trackerError({ response: { status: 500 } })).toBe(TRACKER_UNAVAILABLE);
    expect(trackerError({ isAxiosError: true, request: {}, message: 'Network Error' })).toBe(TRACKER_UNAVAILABLE);
    expect(trackerError({ isAxiosError: true, request: {}, code: 'ECONNABORTED', message: 'timeout of 30000ms exceeded' })).toBe(TRACKER_UNAVAILABLE);
    expect(trackerError({ response: { status: 409, data: { message: 'This step changed since you loaded it; reload and try again' } } }))
      .toBe('This step changed since you loaded it; reload and try again');
    expect(trackerError({ response: { status: 403, data: { message: 'Only superadmin can sign off' } } })).toBe('Only superadmin can sign off');
    expect(trackerError(new Error('The tracker response was not understood.'))).toBe('The tracker response was not understood.');
    expect(trackerError({ response: { status: 404 } }, 'Unable to load this order.')).toBe('Unable to load this order.');
  });
});
