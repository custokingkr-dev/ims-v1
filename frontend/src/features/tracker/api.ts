import api from '../../services/api';
import { parseProductCategories } from '../catalog/api';
import { createNotebookTrackerClient } from '../../generated/notebookTrackerClient';
import { createCatalogClient } from '../../generated/catalogClient';
import { createOperatorAdminClient } from '../../generated/operatorAdminClient';
import { createTenantSchoolClient } from '../../generated/tenantSchoolClient';
import type { DesignerRow, DraftImage, OperatorUser, OrderSummary, SlaRow, TrackerView, WorklistItem } from './types';

export interface OperatorRow { assignmentId: number; userId: number; fullName: string; email: string; schoolId: number; catalogCategory: string | null }

const client = createNotebookTrackerClient(api);
const catalog = createCatalogClient(api);
const operatorAdmin = createOperatorAdminClient(api);
const tenant = createTenantSchoolClient(api);
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
    const assets = await catalog.listOrderAssets({ id: orderId });
    return (Array.isArray(assets) ? assets : []).filter((a) => a.assetKind === 'DESIGN_DRAFT' && !a.supersededAt) as unknown as DraftImage[];
  },
  uploadDraft: async (orderId: string, file: File) => { await catalog.uploadOrderAsset({ id: orderId }, { assetKind: 'DESIGN_DRAFT', file }); },
  deleteDraft: (orderId: string, assetId: number) => catalog.deleteOrderAsset({ id: orderId, assetId }),
  draftBlob: (orderId: string, assetId: number) => catalog.getOrderAssetContent({ id: orderId, assetId }),

  // The order itself lives in school-core: its summary, and starting a tracker whose creation failed at placement.
  summary: (orderId: string): Promise<OrderSummary> => catalog.getNotebookTrackerSummary({ id: orderId }) as Promise<OrderSummary>,
  startTracker: (orderId: string) => catalog.ensureNotebookTracker({ id: orderId }),

  operatorUsers: async (): Promise<OperatorUser[]> => {
    const users = await operatorAdmin.listUsers({ role: 'OPERATIONS', active: true, limit: 500 });
    return (Array.isArray(users) ? users : []).filter((u) => u.id > 0).map((u) => ({ userId: u.id, fullName: u.fullName ?? '', email: u.email ?? '' }));
  },
  // An operator may only be scoped to an item the catalogue has, so the choices come from the catalogue itself.
  categories: async (): Promise<Array<{ code: string; label: string }>> =>
    parseProductCategories(await catalog.listProductCategories()).filter((c) => c.active).map((c) => ({ code: c.code, label: c.label })),
  scopeOperator: async (userId: number, schoolId: number, catalogCategory: string) => { await operatorAdmin.assignOperatorScope({ userId }, { schoolId, catalogCategory }); },
  revokeAssignment: (userId: number, assignmentId: number) => operatorAdmin.revokeRoleAssignment({ userId, assignmentId }),
  createScopedOperator: (schoolId: number, body: { fullName: string; email: string; temporaryPassword: string }, catalogCategory: string) =>
    operatorAdmin.provisionSchoolUser({ schoolId, role: 'OPERATIONS' }, { ...body, catalogCategory }),
  operators: (): Promise<OperatorRow[]> => operatorAdmin.listOperators(),
  schools: async (): Promise<Array<{ id: number; name: string }>> => {
    const rows = await tenant.listSuperadminSchools();
    return Array.isArray(rows) ? rows : [];
  },
};
