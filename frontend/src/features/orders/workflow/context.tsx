import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { workflowApi, workflowError } from './api';
import type {
  CallContacted, CallOutcome, DesignFile, OrderWorkflowSummary, OrderWorkflowView, Stage, Tab, VendorCategoryView, VendorView,
  WorkflowTemplate,
} from './types';

export interface WorkflowUser { userId: number; fullName: string; superadmin: boolean }

export interface DesignFilesState {
  /** Every design file, removed ones included, oldest first; null while loading. */
  files: DesignFile[] | null;
  error: string;
  reload: () => Promise<void>;
}

export interface DirectoryState {
  vendors: VendorView[] | null;
  categories: VendorCategoryView[] | null;
  error: string;
  /** Loads the directory the first time a card needs it. */
  request: () => void;
  retry: () => void;
}

export interface CallPreset { contacted: CallContacted; outcome: CallOutcome }

export interface WorkflowContextValue {
  view: OrderWorkflowView;
  template: WorkflowTemplate;
  stages: Stage[];
  summary: OrderWorkflowSummary | null;
  summaryError: string;
  user: WorkflowUser;
  offsetMs: number;
  busy: boolean;
  /**
   * Runs an action that answers with the new order view. On success the view is replaced and the toast shown; on a refusal
   * the server's words are shown, and on a conflict the view is reloaded first. Resolves to the new view, or null.
   */
  mutate: (action: () => Promise<OrderWorkflowView>, success?: string) => Promise<OrderWorkflowView | null>;
  /** Runs an action that does not answer with the view (a note), then reloads the view. Resolves to whether it worked. */
  run: (action: () => Promise<unknown>, success?: string) => Promise<boolean>;
  reload: () => Promise<void>;
  toast: (text: string, ok?: boolean) => void;
  goToStage: (order: number, tab?: Tab) => void;
  /** Opens the School & vendor tab with the call form preset (School Feedback's "+ Add"). */
  openCallForm: (preset: CallPreset) => void;
  callPreset: CallPreset | null;
  designFiles: DesignFilesState;
  directory: DirectoryState;
  /** Bumped whenever a call is logged, so the sidebar's school feedback re-reads the call log. */
  callsVersion: number;
  callLogged: () => void;
}

export const WorkflowContext = createContext<WorkflowContextValue | null>(null);

export function useWorkflow(): WorkflowContextValue {
  const value = useContext(WorkflowContext);
  if (!value) throw new Error('useWorkflow is used outside an order workflow page.');
  return value;
}

/** The order's design files, read from school-core and re-read after every upload or removal. */
export function useDesignFiles(orderId: string): DesignFilesState {
  const [files, setFiles] = useState<DesignFile[] | null>(null);
  const [error, setError] = useState('');
  const current = useRef(orderId);
  const reload = useCallback(async () => {
    current.current = orderId;
    try {
      const next = await workflowApi.designFiles(orderId);
      if (current.current === orderId) { setFiles(next); setError(''); }
    } catch (e) {
      if (current.current === orderId) setError(workflowError(e, 'The design files could not be loaded.'));
    }
  }, [orderId]);
  useEffect(() => { setFiles(null); void reload(); }, [reload]);
  return { files, error, reload };
}

/** The vendor directory (active vendors and the categories), loaded once, on first use. */
export function useDirectory(): DirectoryState {
  const [vendors, setVendors] = useState<VendorView[] | null>(null);
  const [categories, setCategories] = useState<VendorCategoryView[] | null>(null);
  const [error, setError] = useState('');
  const [wanted, setWanted] = useState(0);
  const loading = useRef(false);
  useEffect(() => {
    if (!wanted || loading.current || (vendors && categories)) return;
    loading.current = true;
    Promise.all([workflowApi.vendors(), workflowApi.vendorCategories()]).then(([nextVendors, nextCategories]) => {
      setVendors(nextVendors);
      setCategories([...nextCategories].sort((left, right) => left.sortOrder - right.sortOrder || left.name.localeCompare(right.name)));
      setError('');
    }, (e: unknown) => setError(workflowError(e, 'The vendor directory could not be loaded.'))).finally(() => { loading.current = false; });
  }, [wanted, vendors, categories]);
  const request = useCallback(() => setWanted((value) => (value ? value : 1)), []);
  const retry = useCallback(() => { setError(''); setWanted((value) => value + 1); }, []);
  return { vendors, categories, error, request, retry };
}
