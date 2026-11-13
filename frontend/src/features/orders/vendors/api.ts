import api from '../../../services/api';
import { createCatalogClient } from '../../../generated/catalogClient';
import { createOperatorAdminClient, type OperatorAssignment } from '../../../generated/operatorAdminClient';
import {
  createOrderWorkflowClient, type DesignerRow, type InternalApprovalGroupView, type TimeSlotRow, type WorkflowDefinitionId,
  type WorkflowTemplate,
} from '../../../generated/orderWorkflowClient';
import { createTenantSchoolClient } from '../../../generated/tenantSchoolClient';
import {
  createVendorDirectoryClient, type VendorCategoryRequest, type VendorCategoryView, type VendorRequest, type VendorView,
} from '../../../generated/vendorDirectoryClient';
import { parseProductCategories } from '../../catalog/api';
import { NOT_UNDERSTOOD, WORKFLOW_UNAVAILABLE, parseList, workflowError } from '../shared/errors';
import { WORKFLOW_DEFINITIONS, workflowDefinitionLabel } from './codes';

export { WORKFLOW_DEFINITIONS, workflowDefinitionLabel };

/**
 * The calls behind Workflow set-up (design section 4.7): the vendor directory, designers, default and per-school time
 * slots, WhatsApp groups and operator assignments. Every list is checked to be a list, so a response the screen cannot
 * read is reported instead of being shown as "nothing here".
 */

export type {
  DesignerRow, InternalApprovalGroupView, TimeSlotRow, VendorCategoryRequest, VendorCategoryView, VendorRequest, VendorView, WorkflowDefinitionId, WorkflowTemplate,
};
export type OperatorRow = OperatorAssignment;
export interface OperatorUser { userId: number; fullName: string; email: string }
export interface SchoolOption { id: number; name: string }
export type SlaUnit = 'HOURS' | 'WORKING_DAYS';
/** The two per-school group links superadmin still sets; vendor groups live on the vendor record (D34). */
export type SchoolGroupTarget = 'SCHOOL_GROUP' | 'INTERNAL_APPROVAL';

const workflows = createOrderWorkflowClient(api);
const directory = createVendorDirectoryClient(api);
const catalog = createCatalogClient(api);
const operatorAdmin = createOperatorAdminClient(api);
const tenant = createTenantSchoolClient(api);

export { NOT_UNDERSTOOD, WORKFLOW_UNAVAILABLE, parseList, workflowError };

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

function parseRecord<T>(value: unknown): T {
  if (!isRecord(value)) throw new Error(NOT_UNDERSTOOD);
  return value as T;
}

export function parseGroupLinks(value: unknown): Record<string, string> {
  if (!isRecord(value) || Object.values(value).some((link) => typeof link !== 'string')) throw new Error(NOT_UNDERSTOOD);
  return value as Record<string, string>;
}

export const setupApi = {
  // Vendor directory (D31, D32): superadmin edits; the list includes inactive vendors so they can be reactivated.
  vendorCategories: async (): Promise<VendorCategoryView[]> => parseList<VendorCategoryView>(await directory.listVendorCategories()),
  updateVendorCategory: async (code: string, request: VendorCategoryRequest): Promise<VendorCategoryView> =>
    parseRecord<VendorCategoryView>(await directory.updateVendorCategory({ code }, request)),
  vendors: async (): Promise<VendorView[]> => parseList<VendorView>(await directory.listVendors({ includeInactive: true })),
  createVendor: async (request: VendorRequest): Promise<VendorView> => parseRecord<VendorView>(await directory.createVendor(request)),
  updateVendor: async (id: number, request: VendorRequest): Promise<VendorView> => parseRecord<VendorView>(await directory.updateVendor({ id }, request)),

  designers: async (): Promise<DesignerRow[]> => parseList<DesignerRow>(await workflows.listDesigners()),
  createDesigner: (name: string, phone: string): Promise<DesignerRow> => workflows.createDesigner({ name, phone, active: null }),
  // The server wants the whole designer back: name and phone are sent on every update.
  updateDesigner: (id: number, body: { name: string; phone: string | null; active: boolean }): Promise<DesignerRow> => workflows.updateDesigner({ id }, body),

  // Time slots (D23): a template step's default, and per-school overrides of it.
  templates: async (): Promise<WorkflowTemplate[]> => parseList<WorkflowTemplate>(await workflows.listWorkflowTemplates()),
  setDefaultSla: async (definitionId: WorkflowDefinitionId, step: number, value: number, unit: SlaUnit): Promise<WorkflowTemplate> =>
    parseRecord<WorkflowTemplate>(await workflows.setTemplateStepSla({ definitionId, step }, { value, unit })),
  timeSlots: async (schoolId: number, definitionId: WorkflowDefinitionId): Promise<TimeSlotRow[]> =>
    parseList<TimeSlotRow>(await workflows.listTimeSlots({ schoolId, definitionId })),
  setTimeSlot: (schoolId: number, definitionId: WorkflowDefinitionId, step: number, value: number, unit: SlaUnit) =>
    workflows.setTimeSlot({ schoolId, definitionId, step }, { value, unit }),
  clearTimeSlot: (schoolId: number, definitionId: WorkflowDefinitionId, step: number) => workflows.clearTimeSlot({ schoolId, definitionId, step }),

  // WhatsApp groups (D34): one global internal approval group, and each school's group and own approval group.
  internalApprovalGroup: async (): Promise<InternalApprovalGroupView> => parseRecord<InternalApprovalGroupView>(await workflows.getInternalApprovalGroup()),
  setInternalApprovalGroup: async (inviteUrl: string | null): Promise<InternalApprovalGroupView> =>
    parseRecord<InternalApprovalGroupView>(await workflows.setInternalApprovalGroup({ inviteUrl })),
  schoolGroups: async (schoolId: number): Promise<Record<string, string>> => parseGroupLinks(await workflows.getSchoolWhatsAppGroupLinks({ schoolId })),
  // An empty url removes the link.
  saveSchoolGroup: async (schoolId: number, target: SchoolGroupTarget, url: string): Promise<Record<string, string>> =>
    parseGroupLinks(await workflows.saveSchoolWhatsAppGroupLink({ schoolId, target }, { url })),

  // Operators (existing set-up, moved here from the retired notebook tracker).
  operators: async (): Promise<OperatorRow[]> => parseList<OperatorRow>(await operatorAdmin.listOperators()),
  operatorUsers: async (): Promise<OperatorUser[]> => {
    const users = parseList<Awaited<ReturnType<typeof operatorAdmin.listUsers>>[number]>(await operatorAdmin.listUsers({ role: 'OPERATIONS', active: true, limit: 500 }));
    return users.filter((user) => user.id > 0).map((user) => ({ userId: user.id, fullName: user.fullName ?? '', email: user.email ?? '' }));
  },
  // An operator may only be scoped to an item the catalogue has, so the choices come from the catalogue itself.
  categories: async (): Promise<Array<{ code: string; label: string }>> =>
    parseProductCategories(await catalog.listProductCategories()).filter((category) => category.active).map((category) => ({ code: category.code, label: category.label })),
  scopeOperator: async (userId: number, schoolId: number, catalogCategory: string) => { await operatorAdmin.assignOperatorScope({ userId }, { schoolId, catalogCategory }); },
  revokeAssignment: (userId: number, assignmentId: number) => operatorAdmin.revokeRoleAssignment({ userId, assignmentId }),
  createScopedOperator: (schoolId: number, body: { fullName: string; email: string; temporaryPassword: string }, catalogCategory: string) =>
    operatorAdmin.provisionSchoolUser({ schoolId, role: 'OPERATIONS' }, { ...body, catalogCategory }),
  schools: async (): Promise<SchoolOption[]> =>
    parseList<Awaited<ReturnType<typeof tenant.listSuperadminSchools>>[number]>(await tenant.listSuperadminSchools()).map((school) => ({ id: school.id, name: school.name })),
};
