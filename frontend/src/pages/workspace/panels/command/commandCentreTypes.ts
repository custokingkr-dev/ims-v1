import type { SuggestedAction } from '../../../../types/workspace';
import type { PanelKey } from '../../config';

export type PolCode =
  | 'FEE_REMINDER'
  | 'FEE_DOWNLOAD'
  | 'PROFILE_UPLOAD'
  | 'PROMOTION_REVIEW'
  | 'PROMOTION_EXCEPTIONS'
  | 'ORDER_VALUE'
  | 'ORDER_FOLLOWUP'
  | 'ORDER_ESCALATE'
  | 'FF_QUOTATION_ADD'
  | 'FF_QUOTATION_VIEW'
  | 'ATTENDANCE_SECTIONS'
  | 'ATTENDANCE_LOW'
  | 'PARENT_NOTIFY';

/**
 * SuggestedAction extended with two-CTA support and derivation metadata.
 * All new fields are optional so existing mock-fixture cards are valid as-is.
 *
 * Primary CTA behaviour:
 *   - If `primaryPolCode` is set → opens a proof-of-life modal (no panel navigation)
 *   - Otherwise → navigates to the panel returned by panelForCard()
 *
 * Secondary CTA behaviour (cta2):
 *   - If `cta2PanelKey` is set → navigates to that panel
 *   - If `cta2PolCode` is set  → opens the corresponding POL modal
 */
export interface CommandCentreCard extends SuggestedAction {
  count?: number;
  amount?: number;           // paise; used for display in POL modals
  primaryPolCode?: PolCode;  // primary opens modal instead of navigating
  cta2?: string;             // secondary CTA label (omit to show no second button)
  cta2PanelKey?: PanelKey;  // secondary → navigate
  cta2PolCode?: PolCode;    // secondary → open POL modal
}
