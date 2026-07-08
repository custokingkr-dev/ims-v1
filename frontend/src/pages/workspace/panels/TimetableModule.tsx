import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { TimetableGrid } from './TimetableGrid';
import { BellSchedulesPanel } from './setup/BellSchedulesPanel';

interface Props {
  readOnly?: boolean;
  staff?: Array<{ id: number | string; name: string }>;
}

interface AcademicYearOpt { id: string; label: string; active: boolean }

export function TimetableModule({ readOnly, staff }: Props) {
  const [years, setYears] = useState<AcademicYearOpt[]>([]);
  const [yearId, setYearId] = useState('');
  const [showManagePatterns, setShowManagePatterns] = useState(false);
  const [refreshSignal, setRefreshSignal] = useState(0);

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

  const closeManagePatterns = () => {
    setShowManagePatterns(false);
    setRefreshSignal((n) => n + 1);
  };

  return (
    <ModuleShell title="Timetable" subtitle="Weekly class schedule">
      <div className="ck-panel-stack">
        <div className="ck-actions-inline" style={{ justifyContent: 'flex-end' }}>
          <select value={yearId} onChange={(e) => setYearId(e.target.value)}>
            {years.map((y) => <option key={y.id} value={y.id}>{y.label}{y.active ? ' (current)' : ''}</option>)}
          </select>
        </div>
        <TimetableGrid
          readOnly={readOnly}
          staff={staff}
          yearId={yearId}
          years={years}
          embedded
          refreshSignal={refreshSignal}
          onManagePatterns={() => setShowManagePatterns(true)}
        />
      </div>

      {showManagePatterns && (
        <div className="ck-modal-bg" onClick={closeManagePatterns}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 900 }}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Manage period patterns</div>
              <button type="button" className="ck-modal-x" onClick={closeManagePatterns}>×</button>
            </div>
            <div className="ck-modal-body">
              <BellSchedulesPanel embedded />
            </div>
            <div className="ck-modal-foot">
              <button type="button" className="ck-btn ck-btn-g" onClick={closeManagePatterns}>Done</button>
            </div>
          </div>
        </div>
      )}
    </ModuleShell>
  );
}
