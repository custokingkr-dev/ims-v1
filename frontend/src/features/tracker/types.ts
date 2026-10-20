// The tracker's wire types come from its OpenAPI contract (contracts/openapi/notebookTrackerClient.v1.openapi.json),
// which is bound to the Java records. What school-core and identity own comes from their contracts the same way, so a
// change on either side fails to compile here instead of drifting.
import type {
  TrackerCapabilities, TrackerContact, TrackerDesign, TrackerDesigner, TrackerDesignerRow, TrackerFollowUp, TrackerListItem,
  TrackerNote, TrackerProgress, TrackerSla, TrackerSlaRow, TrackerStep, TrackerView,
} from '../../generated/notebookTrackerClient';
import type { NotebookTrackerSummary, OrderAsset } from '../../generated/catalogClient';
import type { OperatorAssignment } from '../../generated/operatorAdminClient';

export type {
  TrackerCapabilities, TrackerContact, TrackerDesign, TrackerDesigner, TrackerFollowUp, TrackerNote, TrackerProgress, TrackerSla, TrackerStep, TrackerView,
};
export type StepStatus = TrackerStep['status'];
export type WorklistItem = TrackerListItem;
export type DesignerRow = TrackerDesignerRow;
export type SlaRow = TrackerSlaRow;

/** What school-core knows about the order: its lines, delivery date, the school's own notes and the school's onboarding contact. */
export type OrderSummary = NotebookTrackerSummary;
/** A current design draft of the order, as school-core lists it. */
export type DraftImage = OrderAsset;
/** One operator assignment at a school; `catalogCategory` is null for a school-wide one. */
export type OperatorRow = OperatorAssignment;
export interface OperatorUser { userId: number; fullName: string; email: string }
