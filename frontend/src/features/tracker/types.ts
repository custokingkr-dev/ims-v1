// The tracker's wire types come from its OpenAPI contract (contracts/openapi/notebookTrackerClient.v1.openapi.json),
// which is bound to the Java records. Only what school-core and identity own is described here.
import type {
  TrackerCapabilities, TrackerContact, TrackerDesign, TrackerDesigner, TrackerDesignerRow, TrackerFollowUp, TrackerListItem,
  TrackerNote, TrackerProgress, TrackerSla, TrackerSlaRow, TrackerStep, TrackerView,
} from '../../generated/notebookTrackerClient';

export type {
  TrackerCapabilities, TrackerContact, TrackerDesign, TrackerDesigner, TrackerFollowUp, TrackerNote, TrackerProgress, TrackerSla, TrackerStep, TrackerView,
};
export type StepStatus = TrackerStep['status'];
export type WorklistItem = TrackerListItem;
export type DesignerRow = TrackerDesignerRow;
export type SlaRow = TrackerSlaRow;

/** What school-core knows about the order: its lines, delivery date, the school's own notes and the school's onboarding contact. */
export interface OrderSummary {
  orderId: string; schoolId: number; schoolName: string | null; status: string; customised: boolean; requiredByDate: string | null;
  placedAt: string | null; schoolNotes: string | null; orderedCount: number; trackerPending: boolean;
  schoolContactEmail: string | null; schoolContactPhone: string | null;
  lines: Array<{ lineNo: number; selections: Record<string, string | { code: string; label: string; spec?: string; specText?: string }>; bookCount: number; pageCount: number }>;
}
export interface OperatorUser { userId: number; fullName: string; email: string }
export interface DraftImage { id: number; contentType: string; originalFilename: string; sizeBytes: number; uploadedAt: string }
