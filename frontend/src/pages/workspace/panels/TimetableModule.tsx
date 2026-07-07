import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { TimetableGrid } from './TimetableGrid';
import { BellSchedulesPanel } from './setup/BellSchedulesPanel';
import { SubjectsMasterPanel } from './setup/SubjectsMasterPanel';

interface Props {
  readOnly?: boolean;
  staff?: Array<{ id: number | string; name: string }>;
}

interface AcademicYearOpt { id: string; label: string; active: boolean }

type TabKey = 'grid' | 'bell' | 'subjects';

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'grid', label: 'Weekly Grid' },
  { key: 'bell', label: 'Bell Schedules' },
  { key: 'subjects', label: 'Subjects' },
];

export function TimetableModule({ readOnly, staff }: Props) {
  const [tab, setTab] = useState<TabKey>('grid');
  const [years, setYears] = useState<AcademicYearOpt[]>([]);
  const [yearId, setYearId] = useState('');
  const [bellInitialClassId, setBellInitialClassId] = useState<string | undefined>();

  useEffect(() => {
    void api.get<AcademicYearOpt[]>('/academic-years')
      .then((r) => {
        const list = Array.isArray(r.data) ? r.data : [];
        setYears(list);
        const active = list.find((y) => y.active);
        setYearId((prev) => prev || active?.id || list[0]?.id || '');
      })
      .catch(() => setYears([]));
  }, []);

  return (
    <ModuleShell title="Timetable" subtitle="Weekly grid, bell schedules & subjects">
      <div className="ck-panel-stack">
        <div className="ck-actions-inline" style={{ justifyContent: 'flex-end' }}>
          <select value={yearId} onChange={(e) => setYearId(e.target.value)}>
            {years.map((y) => <option key={y.id} value={y.id}>{y.label}{y.active ? ' (current)' : ''}</option>)}
          </select>
        </div>
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
        {tab === 'grid' && (
          <TimetableGrid
            readOnly={readOnly}
            staff={staff}
            yearId={yearId}
            years={years}
            embedded
            onNeedBellSetup={(classId) => { setBellInitialClassId(classId); setTab('bell'); }}
          />
        )}
        {tab === 'bell' && <BellSchedulesPanel embedded initialClassId={bellInitialClassId} />}
        {tab === 'subjects' && <SubjectsMasterPanel yearId={yearId} years={years} embedded />}
      </div>
    </ModuleShell>
  );
}
