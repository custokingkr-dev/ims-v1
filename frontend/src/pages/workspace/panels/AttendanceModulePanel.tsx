import { useState } from 'react';
import { AttendancePanel } from './AttendancePanel';
import { AttendanceReportsPanel } from './AttendanceReportsPanel';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

export function AttendanceModulePanel({ onRefresh, schoolScopedParams }: Props) {
  const [tab, setTab] = useState<'mark' | 'reports'>('mark');
  return (
    <div>
      <div className="ck-att-tabs">
        <button type="button" className={`ck-att-tab${tab === 'mark' ? ' ck-att-tab--active' : ''}`} onClick={() => setTab('mark')}>
          Mark
        </button>
        <button type="button" className={`ck-att-tab${tab === 'reports' ? ' ck-att-tab--active' : ''}`} onClick={() => setTab('reports')}>
          Reports
        </button>
      </div>
      {tab === 'mark'
        ? <AttendancePanel onRefresh={onRefresh} schoolScopedParams={schoolScopedParams} />
        : <AttendanceReportsPanel schoolScopedParams={schoolScopedParams} />}
    </div>
  );
}
