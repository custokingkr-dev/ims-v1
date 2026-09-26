import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field, thStyle, tdStyle } from '../ui';
import type { PanelKey } from '../config';
import { useWorkspaceDraft } from '../WorkspaceDrafts';

type ZoneSchool = { id: number; schoolId: number; schoolName: string; schoolShortCode: string; schoolCity?: string; schoolState?: string; active: boolean; zoneName: string };

export function ZoneAdminPanel({ zoneId, zoneName, view, setPanel }: { zoneId?: number | null; zoneName?: string | null; view: 'overview' | 'schools'; setPanel: (panel: PanelKey) => void }) {
  const [schools, setSchools] = useState<ZoneSchool[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useWorkspaceDraft('zone.schoolSearch', '');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let alive = true;
    if (!zoneId) { setLoading(false); return; }
    setLoading(true);
    setError('');
    api.get<ZoneSchool[]>(`/zones/${zoneId}/schools`, { params: { active: true } })
      .then(response => { if (alive) setSchools(Array.isArray(response.data) ? response.data : []); })
      .catch((failure) => { if (alive) setError(failure?.response?.data?.message || 'Unable to load your assigned schools. Try again.'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [zoneId, attempt]);
  const filtered = schools.filter(school => [school.schoolName, school.schoolShortCode, school.schoolCity, school.schoolState].join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  return <ModuleShell title={view === 'overview' ? 'Zone overview' : 'My schools'} subtitle={zoneName || schools[0]?.zoneName || 'Schools assigned to your zone'} actions={<button className="ck-btn ck-btn-ghost" disabled={loading || !zoneId} onClick={() => setAttempt(value => value + 1)}>Refresh schools</button>}>
    {!zoneId ? <div className="ck-alert ck-alert-am" role="status">Your account does not have a zone assignment. Ask your platform administrator to assign your zone.</div>
      : loading ? <p role="status">Loading assigned schools…</p>
      : error ? <div role="alert" className="ck-alert ck-alert-re">{error}<button className="ck-btn ck-btn-ghost" onClick={() => setAttempt(value => value + 1)}>Retry</button></div>
      : view === 'overview' ? <section className="ck-card" style={{ padding: 24 }}>
        <h2>{schools.length} assigned {schools.length === 1 ? 'school' : 'schools'}</h2>
        <p>Review the current school assignments and their locations in your zone.</p>
        <button className="ck-btn ck-btn-g" onClick={() => setPanel('za-schools')}>View my schools</button>
      </section> : <section>
        <Field label="Find an assigned school"><input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="School name, code, or location" /></Field>
        <p role="status">{filtered.length} of {schools.length} assigned schools</p>
        <div className="ck-table-wrap"><table style={{ width: '100%', borderCollapse: 'collapse' }}><caption className="sr-only">Schools assigned to your zone</caption><thead><tr>{['School', 'Code', 'Location'].map(label => <th key={label} scope="col" style={thStyle}>{label}</th>)}</tr></thead>
          <tbody>{filtered.map(school => <tr key={school.id}><td style={tdStyle}>{school.schoolName}</td><td style={tdStyle}>{school.schoolShortCode || '—'}</td><td style={tdStyle}>{[school.schoolCity, school.schoolState].filter(Boolean).join(', ') || 'Not provided'}</td></tr>)}</tbody></table></div>
        {!filtered.length && <p>{schools.length ? 'No schools match your search.' : 'No active schools are assigned to this zone.'}</p>}
      </section>}
  </ModuleShell>;
}
