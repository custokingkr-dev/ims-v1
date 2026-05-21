import { useState } from 'react';
import type { PanelKey, WorkspaceData } from '../../pages/workspace/config';

/**
 * Core workspace shell state — panel routing, workspace data load, saving indicator.
 * Used by UnifiedWorkspacePage as the central orchestrator.
 */
export function useWorkspaceCore(initialPanel: PanelKey) {
  const [workspace, setWorkspace] = useState<WorkspaceData | null>(null);
  const [workspaceError, setWorkspaceError] = useState('');
  const [panel, setPanel] = useState<PanelKey>(initialPanel);
  const [saving, setSaving] = useState('');

  return {
    workspace, setWorkspace,
    workspaceError, setWorkspaceError,
    panel, setPanel,
    saving, setSaving,
  };
}
