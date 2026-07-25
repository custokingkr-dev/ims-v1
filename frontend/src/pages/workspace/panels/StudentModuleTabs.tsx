import { FileSpreadsheet, UserPlus, UsersRound } from 'lucide-react';
import type { PanelKey } from '../config';

type StudentPanelKey = 'students' | 'addstudent' | 'bulkimport';

interface Props {
  active: StudentPanelKey;
  setPanel?: (key: PanelKey) => void;
  canCreate?: boolean;
  canImport?: boolean;
}

const tabs: Array<{
  key: StudentPanelKey;
  label: string;
  icon: typeof UsersRound;
  permission: 'read' | 'create' | 'import';
}> = [
  { key: 'students', label: 'Students', icon: UsersRound, permission: 'read' },
  { key: 'addstudent', label: 'Add Student', icon: UserPlus, permission: 'create' },
  { key: 'bulkimport', label: 'Bulk Import', icon: FileSpreadsheet, permission: 'import' },
];

export function StudentModuleTabs({
  active,
  setPanel,
  canCreate = true,
  canImport = true,
}: Props) {
  const visibleTabs = tabs.filter((tab) => (
    tab.permission === 'read'
    || (tab.permission === 'create' && canCreate)
    || (tab.permission === 'import' && canImport)
  ));

  return (
    <nav className="ck-student-module-tabs" aria-label="Student management">
      {visibleTabs.map((tab) => {
        const Icon = tab.icon;
        const selected = active === tab.key;
        return (
          <button
            key={tab.key}
            type="button"
            className={selected ? 'on' : ''}
            aria-current={selected ? 'page' : undefined}
            onClick={() => setPanel?.(tab.key)}
          >
            <Icon size={16} aria-hidden="true" />
            <span>{tab.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
