import api from '../../services/api';
import { errorMessage, parseProductCategories } from '../catalog/api';
import { createNotebookTrackerClient } from '../../generated/notebookTrackerClient';
import { createCatalogClient } from '../../generated/catalogClient';
import { createOperatorAdminClient } from '../../generated/operatorAdminClient';
import { createTenantSchoolClient } from '../../generated/tenantSchoolClient';
import type { DesignerRow, DraftImage, OperatorRow, OperatorUser, OrderSummary, SlaRow, TrackerView, WorklistItem } from './types';

export type { OperatorRow };

const client = createNotebookTrackerClient(api);
const catalog = createCatalogClient(api);
const operatorAdmin = createOperatorAdminClient(api);
const tenant = createTenantSchoolClient(api);
const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Absent (null) or an object the screen can read; anything else is a response the screen does not understand. */
const nullOrRecord = (v: unknown) => v == null || isRecord(v);

/**
 * A tracker response is trusted only when it has the parts the screen reads; anything else is a bad response, not a blank
 * screen. A wholesale order has no design approval, and an order may have no designer or vendor yet: those are null.
 */
export function parseTracker(data: unknown): TrackerView {
  if (!isRecord(data) || typeof data.orderId !== 'string' || !Array.isArray(data.steps) || !isRecord(data.progress)
    || !nullOrRecord(data.design) || !nullOrRecord(data.designer) || !nullOrRecord(data.vendor)) {
    throw new Error('The tracker response was not understood.');
  }
  return {
    ...(data as unknown as TrackerView),
    design: (data.design ?? null) as TrackerView['design'],
    designer: (data.designer ?? null) as TrackerView['designer'],
    vendor: (data.vendor ?? null) as TrackerView['vendor'],
    steps: (data.steps as unknown[]).filter(isRecord).map((s) => ({ ...s, data: isRecord(s.data) ? s.data : {} }) as unknown as TrackerView['steps'][number]),
    internalNotes: Array.isArray(data.internalNotes) ? data.internalNotes as TrackerView['internalNotes'] : [],
    followUps: Array.isArray(data.followUps) ? data.followUps as TrackerView['followUps'] : [],
  };
}

const view = async (request: Promise<unknown>) => parseTracker(await request);

export const TRACKER_UNAVAILABLE = 'The notebook order tracker is not available right now. Try again in a moment.';

/**
 * What to tell the user when a tracker call fails. A refusal the server explained (a 4xx) keeps its own words; a failing
 * service or network (a 5xx, or no response at all) is said plainly instead of passing on text such as "Gateway upstream
 * error". Anything else, such as a response the screen could not read, keeps its own message.
 */
export function trackerError(error: unknown, fallback?: string): string {
  const failure = error as { response?: { status?: unknown }; isAxiosError?: unknown; request?: unknown } | null | undefined;
  const status = failure?.response?.status;
  const unavailable = typeof status === 'number' ? status >= 500 : !failure?.response && (failure?.isAxiosError === true || failure?.request != null);
  return unavailable ? TRACKER_UNAVAILABLE : errorMessage(error, fallback);
}

export const trackerApi = {
  get: (orderId: string) => view(client.getNotebookTracker({ orderId })),
  saveDraft: (orderId: string, step: number, data: Record<string, unknown>, expectedVersion: number) =>
    view(client.saveNotebookStepDraft({ orderId, step }, { data, expectedVersion })),
  saveStageNote: (orderId: string, step: number, note: string, expectedVersion: number) =>
    view(client.saveNotebookStageNote({ orderId, step }, { note, expectedVersion })),
  complete: (orderId: string, step: number, expectedVersion: number) => view(client.completeNotebookStep({ orderId, step }, { expectedVersion })),
  reopen: (orderId: string, step: number) => view(client.reopenNotebookStep({ orderId, step })),
  // A sign-off approves the data the screen loaded, so it carries that step version; the server refuses it if the step changed since.
  signOff: (orderId: string, step: number, expectedVersion: number) => view(client.signOffNotebookStep({ orderId, step }, { expectedVersion })),
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
    return (Array.isArray(assets) ? assets : []).filter((a) => a.assetKind === 'DESIGN_DRAFT' && !a.supersededAt);
  },
  uploadDraft: async (orderId: string, file: File) => { await catalog.uploadOrderAsset({ id: orderId }, { assetKind: 'DESIGN_DRAFT', file }); },
  deleteDraft: (orderId: string, assetId: number) => catalog.deleteOrderAsset({ id: orderId, assetId }),
  draftBlob: (orderId: string, assetId: number) => catalog.getOrderAssetContent({ id: orderId, assetId }),

  // The order itself lives in school-core: its summary, and starting a tracker whose creation failed at placement.
  summary: (orderId: string): Promise<OrderSummary> => catalog.getNotebookTrackerSummary({ id: orderId }),
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
