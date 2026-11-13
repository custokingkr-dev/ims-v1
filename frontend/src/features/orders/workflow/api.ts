import api from '../../../services/api';
import { NOT_UNDERSTOOD, WORKFLOW_UNAVAILABLE, httpStatus, parseList, workflowError } from '../shared/errors';
import { createOrderWorkflowClient } from '../../../generated/orderWorkflowClient';
import { createVendorDirectoryClient } from '../../../generated/vendorDirectoryClient';
import { createCatalogClient } from '../../../generated/catalogClient';
import { createOperatorAdminClient } from '../../../generated/operatorAdminClient';
import { createTenantSchoolClient } from '../../../generated/tenantSchoolClient';
import type {
  ActivityPage, CallLogEntry, DefinitionId, DesignFile, DesignerRow, GateFailure, ListQuery, OperatorChoice, OrderContactsRequest,
  OrderWorkflowPage, OrderWorkflowSummary, OrderWorkflowView, ProductCategory, ResolvedGroups, VendorCategoryView, VendorView, WorkflowTemplate,
} from './types';

const workflows = createOrderWorkflowClient(api);
const directory = createVendorDirectoryClient(api);
const catalog = createCatalogClient(api);
const operatorAdmin = createOperatorAdminClient(api);
const tenant = createTenantSchoolClient(api);

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

export { NOT_UNDERSTOOD, WORKFLOW_UNAVAILABLE, workflowError };

/**
 * An order view is trusted only when it has the parts the screen reads; anything else is a response the screen does not
 * understand, said plainly, never a blank or crashing page.
 */
export function parseView(value: unknown): OrderWorkflowView {
  if (!isRecord(value) || typeof value.orderId !== 'string' || typeof value.definitionId !== 'string' || !Array.isArray(value.steps)
    || !isRecord(value.progress) || !isRecord(value.can) || !isRecord(value.groups)) {
    throw new Error(NOT_UNDERSTOOD);
  }
  const view = value as unknown as OrderWorkflowView;
  return {
    ...view,
    vendorSelections: isRecord(value.vendorSelections) ? view.vendorSelections : {},
    internalNotes: Array.isArray(value.internalNotes) ? view.internalNotes : [],
    upcomingActions: Array.isArray(value.upcomingActions) ? view.upcomingActions : [],
    contacts: isRecord(value.contacts) ? view.contacts
      : { schoolContactName: null, schoolContactPhone: null, factoryContactName: null, factoryContactPhone: null },
    groups: { ...view.groups, vendors: Array.isArray(view.groups.vendors) ? view.groups.vendors : [] },
    steps: view.steps.filter(isRecord).map((step) => ({
      ...step,
      data: isRecord(step.data) ? step.data : {},
      notes: Array.isArray(step.notes) ? step.notes : [],
      unmet: Array.isArray(step.unmet) ? step.unmet : [],
    })),
  };
}

export function parseTemplate(value: unknown): WorkflowTemplate {
  if (!isRecord(value) || typeof value.definitionId !== 'string' || !Array.isArray(value.steps)) throw new Error(NOT_UNDERSTOOD);
  return value as unknown as WorkflowTemplate;
}

export function parsePage(value: unknown): OrderWorkflowPage {
  if (!isRecord(value) || !Array.isArray(value.items) || !isRecord(value.counts) || typeof value.total !== 'number') throw new Error(NOT_UNDERSTOOD);
  return value as unknown as OrderWorkflowPage;
}

export function isNotFound(error: unknown): boolean {
  return httpStatus(error) === 404;
}

/** Every requirement a refused Complete still needs (409 GATE_UNMET), or an empty list for any other failure. */
export function gateFailures(error: unknown): GateFailure[] {
  const data = (error as { response?: { data?: { code?: unknown; unmet?: unknown } } } | null)?.response?.data;
  if (data?.code !== 'GATE_UNMET' || !Array.isArray(data.unmet)) return [];
  return data.unmet.filter((item): item is GateFailure => isRecord(item) && typeof item.message === 'string');
}

/** A 409 that means somebody else changed the order first: the screen reloads and says so. */
export function isConflict(error: unknown): boolean {
  return httpStatus(error) === 409 && gateFailures(error).length === 0;
}

const templateCache = new Map<string, Promise<WorkflowTemplate>>();
let templatesCache: Promise<WorkflowTemplate[]> | null = null;

/** Only tests reset the module caches between cases. */
export function clearWorkflowCaches(): void {
  templateCache.clear();
  templatesCache = null;
}

const view = async (request: Promise<unknown>) => parseView(await request);

export const workflowApi = {
  list: async (query: ListQuery): Promise<OrderWorkflowPage> => parsePage(await workflows.listOrderWorkflows(query)),
  templates: (): Promise<WorkflowTemplate[]> => {
    templatesCache ??= workflows.listWorkflowTemplates().then((value) => parseList<unknown>(value).map(parseTemplate));
    templatesCache.catch(() => { templatesCache = null; });
    return templatesCache;
  },
  /** Templates change only with a deploy (their SLAs aside), so each is fetched once per page load. */
  template: (definitionId: DefinitionId): Promise<WorkflowTemplate> => {
    let cached = templateCache.get(definitionId);
    if (!cached) {
      cached = workflows.getWorkflowTemplate({ definitionId }).then(parseTemplate);
      cached.catch(() => templateCache.delete(definitionId));
      templateCache.set(definitionId, cached);
    }
    return cached;
  },
  view: (orderId: string) => view(workflows.getOrderWorkflow({ orderId })),
  saveStepData: (orderId: string, step: number, data: Record<string, unknown>, expectedVersion: number) =>
    view(workflows.saveStepData({ orderId, step }, { data, expectedVersion })),
  complete: (orderId: string, step: number, expectedVersion: number) => view(workflows.completeStep({ orderId, step }, { expectedVersion })),
  reopen: (orderId: string, step: number, reason: string, expectedVersion: number) =>
    view(workflows.reopenStep({ orderId, step }, { reason, expectedVersion })),
  signOff: (orderId: string, step: number, expectedVersion: number) => view(workflows.signOffStep({ orderId, step }, { expectedVersion })),
  approveDesign: (orderId: string, comment: string, expectedVersion: number) => view(workflows.approveDesign({ orderId }, { comment, expectedVersion })),
  requestChanges: (orderId: string, comment: string, expectedVersion: number) =>
    view(workflows.requestDesignChanges({ orderId }, { comment, expectedVersion })),
  assignOrder: (orderId: string, userId: number) => view(workflows.assignOrder({ orderId }, { userId })),
  assignStep: (orderId: string, step: number, userId: number) => view(workflows.assignStep({ orderId, step }, { userId })),
  claim: (orderId: string) => view(workflows.claimOrder({ orderId })),
  setVendorSelections: (orderId: string, selections: Record<string, number[]>, expectedVersion: number) =>
    view(workflows.setVendorSelections({ orderId }, { selections, expectedVersion })),
  setDesigner: (orderId: string, designerId: number | null) => view(workflows.setDesigner({ orderId }, { designerId })),
  setContacts: (orderId: string, contacts: OrderContactsRequest) => view(workflows.setContacts({ orderId }, contacts)),
  addNote: (orderId: string, body: string, stepOrder?: number) =>
    workflows.addNote({ orderId }, stepOrder == null ? { body } : { body, stepOrder }),
  calls: async (orderId: string, limit?: number): Promise<CallLogEntry[]> => parseList<CallLogEntry>(await workflows.listCalls({ orderId, limit })),
  activity: async (orderId: string, params: { before?: string; step?: number; limit?: number } = {}): Promise<ActivityPage> => {
    const page = await workflows.getOrderActivity({ orderId, ...params });
    if (!isRecord(page) || !Array.isArray(page.entries)) throw new Error(NOT_UNDERSTOOD);
    return page;
  },
  setSchoolGroup: async (orderId: string, inviteUrl: string): Promise<ResolvedGroups> => workflows.setOrderSchoolGroup({ orderId }, { inviteUrl }),
  designers: async (): Promise<DesignerRow[]> => parseList<DesignerRow>(await workflows.listDesigners()),

  vendors: async (): Promise<VendorView[]> => parseList<VendorView>(await directory.listVendors({})),
  vendorCategories: async (): Promise<VendorCategoryView[]> => parseList<VendorCategoryView>(await directory.listVendorCategories()),

  summary: async (orderId: string): Promise<OrderWorkflowSummary> => {
    const summary = await catalog.getOrderWorkflowSummary({ id: orderId });
    if (!isRecord(summary) || !Array.isArray(summary.lines)) throw new Error(NOT_UNDERSTOOD);
    return summary;
  },
  ensure: (orderId: string) => catalog.ensureOrderWorkflow({ id: orderId }),
  /** The CK id of the order that carries this number, for a link or a paste that names the order by its number (D14). */
  orderIdByNumber: async (orderNumber: string): Promise<string> => {
    const found: unknown = await catalog.getOrderByNumber({ orderNumber });
    if (!isRecord(found) || typeof found.id !== 'string' || !found.id.trim()) throw new Error(NOT_UNDERSTOOD);
    return found.id;
  },
  /** Every design file of the order, removed ones included (they carry supersededAt), oldest first. */
  designFiles: async (orderId: string): Promise<DesignFile[]> => parseList<DesignFile>(await catalog.listOrderAssets({ id: orderId }))
    .filter((asset) => asset.assetKind === 'DESIGN_DRAFT')
    .sort((left, right) => left.uploadedAt.localeCompare(right.uploadedAt) || left.id - right.id),
  uploadDesignFile: async (orderId: string, file: File) => { await catalog.uploadOrderAsset({ id: orderId }, { assetKind: 'DESIGN_DRAFT', file }); },
  deleteDesignFile: (orderId: string, assetId: number) => catalog.deleteOrderAsset({ id: orderId, assetId }),
  designFileBlob: (orderId: string, assetId: number) => catalog.getOrderAssetContent({ id: orderId, assetId }),
  categories: async (): Promise<ProductCategory[]> => parseList<ProductCategory>(await catalog.listProductCategories()),

  /** Operators who may be given this order: scoped to its school, school-wide or for its item. */
  operators: async (schoolId?: number, categoryCode?: string): Promise<OperatorChoice[]> => {
    const rows = parseList<Awaited<ReturnType<typeof operatorAdmin.listOperators>>[number]>(await operatorAdmin.listOperators(schoolId ? { schoolId } : {}));
    const eligible = rows.filter((row) => (schoolId == null || row.schoolId === schoolId)
      && (categoryCode == null || row.catalogCategory == null || row.catalogCategory === categoryCode));
    const byUser = new Map<number, OperatorChoice>();
    for (const row of eligible) if (!byUser.has(row.userId)) byUser.set(row.userId, { userId: row.userId, fullName: row.fullName || row.email });
    return [...byUser.values()].sort((left, right) => left.fullName.localeCompare(right.fullName));
  },
  schools: async (): Promise<Array<{ id: number; name: string; city?: string | null }>> =>
    parseList<{ id: number; name: string; city?: string | null }>(await tenant.listSuperadminSchools())
      .map((school) => ({ id: school.id, name: school.name, city: typeof school.city === 'string' ? school.city : null })),
};
