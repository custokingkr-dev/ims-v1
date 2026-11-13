import type {
  BlockSpec, ChecklistSpec, FieldSpec, GateSpec, OrderWorkflowSummary, OrderWorkflowView, StepSpec, StepView, WorkflowTemplate,
} from './types';

/**
 * Test fixtures for the order workflow screens. Templates come from the operations-service template files as they are on
 * disk, normalised the way the server serialises them (every optional member present, `sla` served as `defaultSla`), so
 * the screens are tested against the real step definitions rather than a copy that could drift.
 */

type Raw = Record<string, unknown>;
const record = (value: unknown): Raw => (value && typeof value === 'object' ? value as Raw : {});
const orNull = <T>(value: unknown): T | null => (value === undefined ? null : value as T);

function field(raw: Raw): FieldSpec {
  return {
    kind: raw.kind as FieldSpec['kind'], label: String(raw.label ?? ''), placeholder: orNull(raw.placeholder), min: orNull(raw.min), max: orNull(raw.max),
    choices: orNull(raw.choices), legacy: raw.legacy === true,
  };
}

function checklist(raw: Raw): ChecklistSpec {
  return { items: (raw.items as string[]) ?? [], signOffItem: orNull(raw.signOffItem), source: (raw.source as ChecklistSpec['source']) ?? 'ours' };
}

function block(raw: Raw): BlockSpec {
  return {
    type: raw.type as BlockSpec['type'], title: orNull(raw.title), fields: orNull(raw.fields), checklist: orNull(raw.checklist), save: orNull(raw.save),
    categories: orNull(raw.categories), suggestedByItem: orNull(raw.suggestedByItem),
  };
}

function gate(raw: Raw): GateSpec {
  return {
    type: raw.type as GateSpec['type'], fields: orNull(raw.fields), field: orNull(raw.field), values: orNull(raw.values), checklist: orNull(raw.checklist),
    except: orNull(raw.except), categories: orNull(raw.categories),
  };
}

export function normaliseTemplate(json: unknown): WorkflowTemplate {
  const raw = record(json);
  return {
    definitionId: raw.definitionId as WorkflowTemplate['definitionId'],
    title: String(raw.title ?? ''),
    introMessage: String(raw.introMessage ?? ''),
    steps: ((raw.steps as Raw[]) ?? []).map((step): StepSpec => ({
      order: Number(step.order), code: String(step.code), title: String(step.title), description: String(step.description ?? ''),
      phase: step.phase as StepSpec['phase'], defaultSla: orNull(step.defaultSla ?? step.sla), customisedOnly: step.customisedOnly === true,
      icon: String(step.icon ?? ''), tabs: (step.tabs as StepSpec['tabs']) ?? ['WORK', 'SCHOOL_VENDOR', 'CHECKLIST', 'ACTIVITY'],
      blocks: ((step.blocks as Raw[]) ?? []).map(block),
      fields: Object.fromEntries(Object.entries(record(step.fields)).map(([key, value]) => [key, field(record(value))])),
      checklists: Object.fromEntries(Object.entries(record(step.checklists)).map(([key, value]) => [key, checklist(record(value))])),
      gates: ((step.gates as Raw[]) ?? []).map(gate),
      signOff: step.signOff ? { label: String(record(step.signOff).label), role: 'SUPERADMIN', banner: orNull(record(step.signOff).banner) } : null,
      superadminCompletes: step.superadminCompletes === true,
    })),
  };
}

export const NOW = '2026-11-13T06:30:00Z';
const HOUR = 3_600_000;
const at = (offsetHours: number) => new Date(Date.parse(NOW) + offsetHours * HOUR).toISOString();

export interface ViewOptions {
  orderId?: string;
  orderNumber?: string | null;
  customised?: boolean;
  /** The active step's order; null for a finished order. */
  active?: number | null;
  /** Lets the signed-in user edit and complete the active step. */
  canAct?: boolean;
  superadmin?: boolean;
  itemCode?: string;
  itemNoun?: string;
  categoryCode?: string;
  steps?: Record<number, Partial<StepView>>;
  view?: Partial<OrderWorkflowView>;
}

/** An order on `template` with every step before `active` completed and every one after it locked. */
export function makeView(template: WorkflowTemplate, options: ViewOptions = {}): OrderWorkflowView {
  const customised = options.customised ?? true;
  const path = template.steps.filter((step) => customised || !step.customisedOnly);
  const active = options.active === undefined ? path[0].order : options.active;
  const canAct = options.canAct ?? true;
  const superadmin = options.superadmin ?? false;
  const steps = path.map((spec, index): StepView => {
    const status = active == null || spec.order < active ? 'COMPLETED' : spec.order === active ? 'ACTIVE' : 'LOCKED';
    const base: StepView = {
      step: spec.order, code: spec.code, title: spec.title, phase: spec.phase, customisedOnly: spec.customisedOnly, status,
      assigneeUserId: 42, assigneeName: 'Asha Operator',
      startedAt: status === 'LOCKED' ? null : at(-2 - (path.length - index)), dueAt: status === 'ACTIVE' ? at(3) : status === 'COMPLETED' ? at(-1) : null,
      overdue: false, sla: spec.defaultSla ? { ...spec.defaultSla, source: 'DEFAULT' } : null,
      completedAt: status === 'COMPLETED' ? at(-1 - (path.length - index)) : null, completedBy: status === 'COMPLETED' ? 42 : null,
      completedByName: status === 'COMPLETED' ? 'Asha Operator' : null,
      data: {}, dataSavedAt: null, dataSavedBy: null, dataSavedByName: null, signOff: null, notes: [], unmet: [], version: 3,
      can: {
        canEdit: status === 'ACTIVE' ? canAct : status === 'COMPLETED' && superadmin,
        canComplete: status === 'ACTIVE' && canAct && (!spec.superadminCompletes || superadmin),
        canReopen: superadmin && status !== 'LOCKED',
        canSignOff: superadmin && status === 'ACTIVE' && !!spec.signOff,
        // WorkflowProgress.canAddNote: a stage not reached yet takes no notes.
        canAddNote: status !== 'LOCKED',
      },
    };
    return { ...base, ...options.steps?.[spec.order] };
  });
  const completed = steps.filter((step) => step.status === 'COMPLETED').length;
  const current = steps.find((step) => step.status === 'ACTIVE');
  return {
    orderId: options.orderId ?? 'CK-1015', orderNumber: options.orderNumber === undefined ? 'NB-DPS_GUR-26-11-13-0042' : options.orderNumber,
    definitionId: template.definitionId, categoryCode: options.categoryCode ?? 'NOTEBOOKS', itemCode: options.itemCode ?? 'NB', itemNoun: options.itemNoun ?? 'notebook',
    schoolId: 7, schoolName: 'Delhi Public School', customised, orderedCount: 15600, placedAt: at(-48), timeZone: 'Asia/Kolkata',
    instanceStatus: current ? 'IN_PROGRESS' : 'COMPLETED', status: current?.code ?? 'COMPLETED',
    progress: { completed, total: steps.length, currentStep: current?.step ?? null, currentCode: current?.code ?? null, currentTitle: current?.title ?? null, finished: !current },
    assigneeUserId: 42, assigneeName: 'Asha Operator',
    design: { state: 'NOT_SENT', comment: null, decidedAt: null, decidedBy: null, decidedByName: null, approvedCdrLink: null },
    vendorSelections: {}, groups: { school: { url: 'https://chat.whatsapp.com/SchoolGroup1234', source: 'SCHOOL' }, internalApproval: { url: null, source: 'NONE' }, vendors: [] },
    designer: null, contacts: { schoolContactName: 'Rajesh Kumar', schoolContactPhone: '+91 98765 43210', factoryContactName: null, factoryContactPhone: null },
    internalNotes: [], upcomingActions: [],
    can: {
      canAssign: superadmin, canClaim: false, canSelectVendors: superadmin || canAct, canSetDesigner: superadmin || canAct, canSetContacts: superadmin || canAct,
      canSetSchoolGroup: superadmin || canAct, canLogCall: true, canAddNote: true, canManageDesignFiles: canAct, canApproveDesign: superadmin,
    },
    now: NOW, version: 5, steps,
    ...options.view,
  };
}

export function makeSummary(over: Partial<OrderWorkflowSummary> = {}): OrderWorkflowSummary {
  return {
    orderId: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042', category: 'NOTEBOOKS', itemCode: 'NB', itemLabel: 'Notebooks', schoolId: 7,
    schoolName: 'Delhi Public School', schoolCity: 'Gurugram', schoolContactEmail: 'office@dps.test', schoolContactPhone: '+91 98765 43210',
    status: 'PRODUCTION_PLANNING', customised: true, requiredByDate: '2026-12-15', placedAt: at(-48), schoolNotes: 'Please make the logo bigger.',
    orderedCount: 15600, lines: [], trackerPending: false,
    quantities: { total: 15600, lines: [{ lineNo: 1, label: 'King', details: 'Single rule · 196 pages', quantity: 8600 }] },
    titleCards: {
      counts: { king: 8600, long: 4000, jumboKing: 0, jumboLong: 3000, fa: 0, other: 0 },
      rows: [
        { type: 'KING_JUMBO_KING', label: 'King / Jumbo King', size: '80*105', notebooks: 8600, sheets: 1088 },
        { type: 'LONG', label: 'Long', size: '72*112', notebooks: 4000, sheets: 513 },
        { type: 'JUMBO_LONG', label: 'Jumbo Long', size: '63.5*76', notebooks: 3000, sheets: 775 },
        { type: 'FA', label: 'FA', size: '23*36', notebooks: 0, sheets: 0 },
      ],
      unmatched: [],
    },
    ...over,
  };
}
