import { useState } from 'react';
import { AttendancePanel } from './AttendancePanel';
import { AttendanceReportsPanel } from './AttendanceReportsPanel';
import { AttendanceAbsenteePanel } from './AttendanceAbsenteePanel';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

type Tab = 'mark' | 'reports' | 'absentees';

export function AttendanceModulePanel({ onRefresh, schoolScopedParams }: Props) {
  const [tab, setTab] = useState<Tab>('mark');
  const label: Record<Tab, string> = { mark: 'Mark', reports: 'Reports', absentees: 'Absentees' };
  return (
    <div>
      <div className="ck-att-tabs">
        {(['mark', 'reports', 'absentees'] as Tab[]).map((t) => (
          <button key={t} type="button" className={`ck-att-tab${tab === t ? ' ck-att-tab--active' : ''}`} onClick={() => setTab(t)}>
            {label[t]}
          </button>
        ))}
      </div>
      {tab === 'mark' && <AttendancePanel onRefresh={onRefresh} schoolScopedParams={schoolScopedParams} />}
      {tab === 'reports' && <AttendanceReportsPanel schoolScopedParams={schoolScopedParams} />}
      {tab === 'absentees' && <AttendanceAbsenteePanel schoolScopedParams={schoolScopedParams} />}
    </div>
  );
}
