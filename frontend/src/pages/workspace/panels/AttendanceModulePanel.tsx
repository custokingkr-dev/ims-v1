import { useState } from 'react';
import { ChartNoAxesCombined, ListChecks, MessageSquareWarning } from 'lucide-react';
import { AttendancePanel } from './AttendancePanel';
import { AttendanceReportsPanel } from './AttendanceReportsPanel';
import { AttendanceAbsenteePanel } from './AttendanceAbsenteePanel';
import { ModuleShell } from '../ui';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

type Tab = 'mark' | 'reports' | 'absentees';

export function AttendanceModulePanel({ onRefresh, schoolScopedParams }: Props) {
  const [tab, setTab] = useState<Tab>('mark');
  const tabs = [
    { id: 'mark' as const, label: 'Daily register', Icon: ListChecks },
    { id: 'absentees' as const, label: 'Exceptions', Icon: MessageSquareWarning },
    { id: 'reports' as const, label: 'Reports', Icon: ChartNoAxesCombined },
  ];

  return (
    <ModuleShell
      title="Attendance"
      subtitle="Mark, review, and follow up from one daily workspace"
    >
      <div className="ck-att-tabs" role="tablist" aria-label="Attendance views">
        {tabs.map(({ id, label, Icon }) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            className={`ck-att-tab${tab === id ? ' ck-att-tab--active' : ''}`}
            onClick={() => setTab(id)}
          >
            <Icon size={16} />
            {label}
          </button>
        ))}
      </div>
      {tab === 'mark' && <AttendancePanel onRefresh={onRefresh} schoolScopedParams={schoolScopedParams} />}
      {tab === 'reports' && <AttendanceReportsPanel schoolScopedParams={schoolScopedParams} />}
      {tab === 'absentees' && <AttendanceAbsenteePanel schoolScopedParams={schoolScopedParams} />}
    </ModuleShell>
  );
}
