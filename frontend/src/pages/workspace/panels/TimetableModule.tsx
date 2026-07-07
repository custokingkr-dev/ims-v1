import { useState } from 'react';
import { ModuleShell } from '../ui';
import { TimetableGrid } from './TimetableGrid';
import { BellSchedulesPanel } from './setup/BellSchedulesPanel';
import { SubjectsMasterPanel } from './setup/SubjectsMasterPanel';

interface Props {
  readOnly?: boolean;
  staff?: Array<{ id: number | string; name: string }>;
}

type TabKey = 'grid' | 'bell' | 'subjects';

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'grid', label: 'Weekly Grid' },
  { key: 'bell', label: 'Bell Schedules' },
  { key: 'subjects', label: 'Subjects' },
];

export function TimetableModule({ readOnly, staff }: Props) {
  const [tab, setTab] = useState<TabKey>('grid');
  return (
    <ModuleShell title="Timetable" subtitle="Weekly grid, bell schedules & subjects">
      <div className="ck-panel-stack">
        <div style={{ display: 'flex', gap: 4, borderBottom: '2px solid var(--border)' }}>
          {TABS.map((t) => (
            <button
              key={t.key}
              className="ck-btn ck-btn-ghost"
              onClick={() => setTab(t.key)}
              style={{
                borderRadius: '6px 6px 0 0',
                borderBottom: tab === t.key ? '2px solid var(--g2)' : '2px solid transparent',
                fontWeight: tab === t.key ? 600 : 400,
                marginBottom: -2,
              }}
            >
              {t.label}
            </button>
          ))}
        </div>
        {tab === 'grid' && <TimetableGrid readOnly={readOnly} staff={staff} />}
        {tab === 'bell' && <BellSchedulesPanel />}
        {tab === 'subjects' && <SubjectsMasterPanel />}
      </div>
    </ModuleShell>
  );
}
