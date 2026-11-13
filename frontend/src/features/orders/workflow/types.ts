// The order workflow's wire types come from its OpenAPI contracts (contracts/openapi/orderWorkflowClient.v1.openapi.json and
// vendorDirectoryClient.v1.openapi.json), which are bound to the operations-service records. What school-core owns (the
// order summary and the design files) comes from its contract the same way, so a change on either side fails to compile
// here instead of drifting.
import type {
  ActivityEntry, ActivityPage, BlockSpec, BlockType, CallContacted, CallLogEntry, CallOutcome, ChecklistSpec, DesignDecision,
  DesignerRow, FieldSpec, GateFailure, GateSpec, GroupLink, NoteEntry, OrderCapabilities, OrderContacts, OrderContactsRequest,
  OrderWorkflowCounts, OrderWorkflowListItem, OrderWorkflowPage, OrderWorkflowView, Phase, ResolvedGroups, SignOffSpec, SlaState,
  StepCapabilities, StepSpec, StepView, Tab, UpcomingAction, VendorSummary, WorkflowDefinitionId, WorkflowTemplate,
} from '../../../generated/orderWorkflowClient';
import type { VendorCategoryView, VendorView } from '../../../generated/vendorDirectoryClient';
import type { OrderAsset, OrderWorkflowSummary, ProductCategory, TitleCardRow, TitleCards } from '../../../generated/catalogClient';
import type { OrderWorkflowClient } from '../../../generated/orderWorkflowClient';

export type {
  ActivityEntry, ActivityPage, BlockSpec, BlockType, CallContacted, CallLogEntry, CallOutcome, ChecklistSpec, DesignDecision, DesignerRow,
  FieldSpec, GateFailure, GateSpec, GroupLink, NoteEntry, OrderCapabilities, OrderContacts, OrderContactsRequest, OrderWorkflowCounts,
  OrderWorkflowListItem, OrderWorkflowPage, OrderWorkflowView, Phase, ResolvedGroups, SignOffSpec, SlaState, StepCapabilities, StepSpec,
  StepView, Tab, UpcomingAction, VendorSummary, WorkflowTemplate, VendorCategoryView, VendorView, OrderWorkflowSummary, ProductCategory,
  TitleCardRow, TitleCards,
};

export type DefinitionId = WorkflowDefinitionId;
/** The list endpoint's query, as the generated client takes it. */
export type ListQuery = NonNullable<Parameters<OrderWorkflowClient['listOrderWorkflows']>[0]>;
/** The list's SLA filter: a row's SLA state, or DUE_TODAY (the "Due today" chip: due on the school's today, overdue or not). */
export type SlaFilter = NonNullable<ListQuery['sla']>;
/** A design file of the order as school-core lists it (asset kind DESIGN_DRAFT); a removed one carries supersededAt. */
export type DesignFile = OrderAsset;
export interface OperatorChoice { userId: number; fullName: string }

/** A step of this order's path together with its template spec. */
export interface Stage {
  view: StepView;
  spec: StepSpec;
}
