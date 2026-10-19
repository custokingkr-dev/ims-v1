import api from '../../services/api';
import { parseProductCategories } from '../catalog/api';
import { createNotebookTrackerClient } from '../../generated/notebookTrackerClient';
import type { DesignerRow, DraftImage, OperatorUser, OrderSummary, SlaRow, TrackerView, WorklistItem } from './types';

export interface OperatorRow { assignmentId: number; userId: number; fullName: string; email: string; schoolId: number; catalogCategory: string | null }

const client = createNotebookTrackerClient(api);
const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** A tracker response is trusted only when it has the parts the screen reads; anything else is a bad response, not a blank screen. */
export function parseTracker(data: unknown): TrackerView {
  if (!isRecord(data) || typeof data.orderId !== 'string' || !Array.isArray(data.steps) || !isRecord(data.progress) || !isRecord(data.design)) {
    throw new Error('The tracker response was not understood.');
  }
  return {
    ...(data as unknown as TrackerView),
    steps: (data.steps as unknown[]).filter(isRecord).map((s) => ({ ...s, data: isRecord(s.data) ? s.data : {} }) as unknown as TrackerView['steps'][number]),
    internalNotes: Array.isArray(data.internalNotes) ? data.internalNotes as TrackerView['internalNotes'] : [],
    followUps: Array.isArray(data.followUps) ? data.followUps as TrackerView['followUps'] : [],
  };
}

const view = async (request: Promise<unknown>) => parseTracker(await request);

export const trackerApi = {
  get: (orderId: string) => view(client.getNotebookTracker({ orderId })),
  saveDraft: (orderId: string, step: number, data: Record<string, unknown>, expectedVersion: number) =>
    view(client.saveNotebookStepDraft({ orderId, step }, { data, expectedVersion })),
  saveStageNote: (orderId: string, step: number, note: string, expectedVersion: number) =>
    view(client.saveNotebookStageNote({ orderId, step }, { note, expectedVersion })),
  complete: (orderId: string, step: number, expectedVersion: number) => view(client.completeNotebookStep({ orderId, step }, { expectedVersion })),
  reopen: (orderId: string, step: number) => view(client.reopenNotebookStep({ orderId, step })),
  signOff: (orderId: string, step: number) => view(client.signOffNotebookStep({ orderId, step })),
  assignStep: (orderId: string, step: number, userId: number) => view(client.assignNotebookStep({ orderId, step }, { userId })),
  assignOrder: (orderId: string, userId: number) => view(client.assignNotebookOrder({ orderId }, { userId })),
  addFollowUp: (orderId: string, body: { percentComplete: number; nextFollowUpOn?: string; contactPhone?: string }) =>
    view(client.addNotebookFollowUp({ orderId }, { percentComplete: body.percentComplete, nextFollowUpOn: body.nextFollowUpOn ?? null, contactPhone: body.contactPhone ?? null })),
  sendDesign: (orderId: string) => view(client.sendNotebookDesign({ orderId })),
  approveDesign: (orderId: string, draftRef: number, comment?: string) => view(client.approveNotebookDesign({ orderId }, { draftRef, comment: comment ?? null })),
  requestChanges: (orderId: string, comment?: string) => view(client.requestNotebookDesignChanges({ orderId }, { comment: comment ?? null })),
  setVendor: (orderId: string, name: string, phone: string) => view(client.setNotebookVendor({ orderId }, { name, phone })),
  setDesigner: (orderId: string, designerId: number) => view(client.setNotebookDesigner({ orderId }, { designerId })),
  addInternalNote: (orderId: string, body: string) => view(client.addNotebookInternalNote({ orderId }, { body })),

  worklist: async (params: { scope: 'mine' | 'all'; schoolId?: number; includeFinished?: boolean }): Promise<WorklistItem[]> => {
    const rows = await client.listNotebookOrders(params);
    return Array.isArray(rows) ? rows : [];
  },
  designers: (): Promise<DesignerRow[]> => client.listNotebookDesigners(),
  createDesigner: (name: string, phone: string): Promise<DesignerRow> => client.createNotebookDesigner({ name, phone, active: null }),
  // The server wants the whole designer back: name and phone are required on every update.
  updateDesigner: (id: number, body: { name: string; phone: string; active: boolean }): Promise<DesignerRow> =>
    client.updateNotebookDesigner({ id }, body),
  timeSlots: (schoolId: number): Promise<SlaRow[]> => client.listNotebookTimeSlots({ schoolId }),
  setTimeSlot: (schoolId: number, step: number, value: number, unit: string) =>
    client.setNotebookTimeSlot({ schoolId, step }, { value, unit: unit as 'HOURS' | 'WORKING_DAYS' }),
  clearTimeSlot: (schoolId: number, step: number) => client.clearNotebookTimeSlot({ schoolId, step }),

  // Design drafts live with the order in school-core; the tracker only decides who may change them.
  draftImages: async (orderId: string): Promise<DraftImage[]> => {
    const { data } = await api.get<Array<Record<string, unknown>>>(`/supply/orders/${encodeURIComponent(orderId)}/assets`);
    return (Array.isArray(data) ? data : []).filter((a) => a.assetKind === 'DESIGN_DRAFT' && !a.supersededAt) as unknown as DraftImage[];
  },
  uploadDraft: async (orderId: string, file: File) => {
    const form = new FormData(); form.append('file', file); form.append('assetKind', 'DESIGN_DRAFT');
    await api.post(`/supply/orders/${encodeURIComponent(orderId)}/assets`, form);
  },
  deleteDraft: async (orderId: string, assetId: number) => { await api.delete(`/supply/orders/${encodeURIComponent(orderId)}/assets/${assetId}`); },
  draftBlob: async (orderId: string, assetId: number) =>
    (await api.get<Blob>(`/supply/orders/${encodeURIComponent(orderId)}/assets/${assetId}/content`, { responseType: 'blob' })).data,

  // The order itself lives in school-core: its summary, and starting a tracker whose creation failed at placement.
  summary: async (orderId: string) => (await api.get<OrderSummary>(`/catalog/orders/${encodeURIComponent(orderId)}/tracker-summary`)).data,
  startTracker: async (orderId: string) => (await api.post<{ trackerPending: boolean }>(`/catalog/orders/${encodeURIComponent(orderId)}/tracker/ensure`)).data,

  operatorUsers: async (): Promise<OperatorUser[]> => {
    const { data } = await api.get<Array<{ id: number; fullName?: string; email?: string }>>('/users', { params: { role: 'OPERATIONS', active: true, limit: 500 } });
    return (Array.isArray(data) ? data : []).filter((u) => u.id > 0).map((u) => ({ userId: u.id, fullName: u.fullName ?? '', email: u.email ?? '' }));
  },
  // An operator may only be scoped to an item the catalogue has, so the choices come from the catalogue itself.
  categories: async (): Promise<Array<{ code: string; label: string }>> =>
    parseProductCategories((await api.get<unknown>('/supply/product-catalog/categories')).data).filter((c) => c.active).map((c) => ({ code: c.code, label: c.label })),
  scopeOperator: async (userId: number, schoolId: number, catalogCategory: string) => { await api.post(`/rbac/users/${userId}/operator-scopes`, { schoolId, catalogCategory }); },
  createScopedOperator: async (schoolId: number, body: { fullName: string; email: string; temporaryPassword: string }, catalogCategory: string) =>
    (await api.post<{ userId: number }>(`/users/provisioning/schools/${schoolId}/users/OPERATIONS`, { ...body, catalogCategory })).data,
  operators: async () => (await api.get<OperatorRow[]>('/rbac/operators')).data,
};
