import { createContext, useContext, useId, type ReactNode } from 'react';
import type { BlockSpec, BlockType, Stage, StepView } from '../types';

export interface StageContextValue {
  stage: Stage;
  /** The server lets this user save data and tick checklists on this stage now (StepCapabilities.canEdit). */
  editable: boolean;
  /** Text typed into this stage's fields and not saved yet, by field key. */
  draft: Record<string, string>;
  setDraft: (key: string, text: string) => void;
  /** Saves the typed values of these fields, a partial save that leaves every other field as it is. */
  saveFields: (keys: string[], success: string) => Promise<boolean>;
  /** Saves exactly this partial data (a checklist tick). */
  saveData: (data: Record<string, unknown>, success: string) => Promise<boolean>;
  /** Saves every typed change on this stage, then resolves to the stage as saved (null when the save failed). */
  flush: () => Promise<StepView | null>;
}

export const StageContext = createContext<StageContextValue | null>(null);

export function useStage(): StageContextValue {
  const value = useContext(StageContext);
  if (!value) throw new Error('useStage is used outside a stage panel.');
  return value;
}

export const DEFAULT_TITLES: Partial<Record<BlockType, string>> = {
  ORDER_PDF_LINK: 'School order PDF link',
  INTRO_MESSAGE: 'School Group Chat & Introduction Message',
  SCHOOL_GROUP: 'School Group Chat',
  DESIGN_FILES: 'Upload Design Files',
  CHECKLIST: 'Checklist',
  CDR_FOR_APPROVAL: 'Design version for approval',
  HEAD_APPROVAL: 'Approval decision',
  ORDER_FOLDER: 'School order folder',
  TITLE_CARD_QUANTITIES: 'Notebook title card quantities',
  SAVED_TITLE_CARDS: 'Saved notebook title card quantities',
  VENDOR_DIRECTORY: 'Supplier Contact Groups',
  VENDOR_SELECTION: 'Select Vendors for This Order',
  VENDOR_GROUPS: 'Vendor Contact Groups',
  INTERNAL_APPROVAL_GROUP: 'Internal Approval Group Link',
  SIGN_OFF: 'Sign-off',
  SEND_BACK: 'School verification',
  LEGACY_DETAILS: 'Earlier details recorded on this stage',
};

export function blockTitle(block: BlockSpec, fallback?: string): string | null {
  return block.title?.trim() || DEFAULT_TITLES[block.type] || fallback || null;
}

/** One stage card: a titled section with an optional badge at the right of its header. */
export function BlockCard({ title, badge, tone, children, className }: {
  title: string | null; badge?: ReactNode; tone?: 'warning' | 'neutral'; children: ReactNode; className?: string;
}) {
  const id = useId();
  return (
    <section className={`ck-wf-block${tone ? ` is-${tone}` : ''}${className ? ` ${className}` : ''}`} aria-labelledby={title ? id : undefined}>
      {(title || badge) && (
        <header className="ck-wf-block-h">
          {title && <h4 id={id}>{title}</h4>}
          {badge}
        </header>
      )}
      {children}
    </section>
  );
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'primary' }) {
  return <span className={`ck-wf-badge is-${tone}`}>{children}</span>;
}

/** The amber "nothing here yet" line the prototype uses wherever a card depends on an earlier stage. */
export function Warning({ children }: { children: ReactNode }) {
  return <p className="ck-wf-warning" role="note">{children}</p>;
}
