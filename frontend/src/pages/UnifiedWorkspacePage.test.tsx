import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import UnifiedWorkspacePage from './UnifiedWorkspacePage';
import api from '../services/api';

const state = vi.hoisted(() => ({ user: { userId: 1, branchId: 7, zoneId: null as number | null, role: 'ADMIN', fullName: 'School admin' }, permissions: ['student:read', 'student:create'], modules: ['ERP'] }));
vi.mock('../services/api');
vi.mock('../contexts/AuthContext', () => ({ useAuth: () => ({ user: state.user, logout: vi.fn() }) }));
vi.mock('../hooks/usePermissions', () => ({ usePermissions: () => ({ can: (code: string) => state.permissions.includes(code), canAny: (codes: string[]) => codes.some(code => state.permissions.includes(code)) }) }));
vi.mock('./workspace/panels/HomePanel', () => ({ HomePanel: () => <p>School dashboard content</p> }));
vi.mock('./workspace/panels/StudentsPanel', () => ({ StudentsPanel: ({ setPanel }: { setPanel: (key: string) => void }) => <button onClick={() => setPanel('addstudent')}>Start admission</button> }));
vi.mock('./workspace/panels/AddStudentPanel', () => ({ AddStudentPanel: () => <p>Admission form content</p> }));

function LocationControls() {
  const location = useLocation();
  const navigate = useNavigate();
  return <><output aria-label="Current URL">{location.search}</output><button onClick={() => navigate(-1)}>Browser Back</button></>;
}
function mount(url: string) {
  return render(<MemoryRouter initialEntries={[url]}><LocationControls /><UnifiedWorkspacePage /></MemoryRouter>);
}
afterEach(cleanup);
beforeEach(() => {
  localStorage.clear();
  state.user = { userId: 1, branchId: 7, zoneId: null, role: 'ADMIN', fullName: 'School admin' };
  state.permissions = ['student:read', 'student:create'];
  state.modules = ['ERP'];
  vi.mocked(api.get).mockReset();
  vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({ data: url.endsWith('/modules/active') ? state.modules.map(moduleCode => ({ moduleCode })) : { school: { name: 'School' }, dashboard: {}, recentActivity: [], staff: [] } }));
});

describe('task-addressable workspace navigation', () => {
  it('restores a bookmarked panel, writes task URLs, and honors browser Back', async () => {
    mount('/dashboard?panel=students');
    fireEvent.click(await screen.findByRole('button', { name: 'Start admission' }));
    expect(await screen.findByText('Admission form content')).toBeInTheDocument();
    expect(screen.getByLabelText('Current URL')).toHaveTextContent('?panel=addstudent');
    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));
    expect(await screen.findByRole('button', { name: 'Start admission' })).toBeInTheDocument();
    expect(document.querySelector('.ck-sidebar')).toHaveClass('pinned');
  });

  it.each(['sa-schools', 'za-schools', 'unknown'])('rejects inaccessible or unknown panel %s', async panel => {
    mount(`/dashboard?panel=${panel}`);
    expect(await screen.findByText('School dashboard content')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Current URL')).toHaveTextContent('?panel=home'));
  });

  it('checks permissions on student subpanel URLs', async () => {
    state.permissions = ['student:read'];
    mount('/dashboard?panel=addstudent');
    expect(await screen.findByText('School dashboard content')).toBeInTheDocument();
    expect(screen.queryByText('Admission form content')).not.toBeInTheDocument();
  });

  it('does not request order data for a denied orders bookmark', async () => {
    mount('/dashboard?panel=orders');
    expect(await screen.findByText('School dashboard content')).toBeInTheDocument();
    expect(vi.mocked(api.get).mock.calls.some(([url]) => url.startsWith('/catalog/orders'))).toBe(false);
  });

  it('checks school module entitlements before opening a bookmark', async () => {
    state.modules = [];
    localStorage.setItem('ck_nav_pinned', '0');
    mount('/dashboard?panel=students');
    expect(await screen.findByText('School dashboard content')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start admission' })).not.toBeInTheDocument();
    expect(document.querySelector('.ck-sidebar')).not.toHaveClass('pinned');
  });
});
