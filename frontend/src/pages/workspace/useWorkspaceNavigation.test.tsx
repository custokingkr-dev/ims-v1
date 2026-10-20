import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useWorkspaceNavigation } from './useWorkspaceNavigation';

afterEach(cleanup);
const permissions = ['student:read', 'student:create', 'firefighting:read', 'firefighting:create'];
function Navigation({ modules }: { modules: Set<string> | null }) {
  const state = useWorkspaceNavigation({
    role: 'ADMIN', activeModules: modules,
    can: code => permissions.includes(code), canAny: codes => codes.some(code => permissions.includes(code)),
  });
  const location = useLocation();
  return <>
    <output aria-label="Task URL">{location.search}</output>
    <output aria-label="Editing request">{state.ffEditingCode ?? ''}</output>
    <output aria-label="Entitlements ready">{String(state.entitlementsReady)}</output>
    <button onClick={() => state.openFfDraft('FF-101')}>Open request draft</button>
    <button onClick={() => state.setPanel('students')}>Open students</button>
  </>;
}

describe('workspace task URLs', () => {
  it('opens request drafts and removes the request when switching tasks', () => {
    render(<MemoryRouter initialEntries={['/dashboard?panel=ff-dashboard']}><Navigation modules={new Set(['ERP', 'SUPPLY_OS'])} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Open request draft' }));
    expect(screen.getByLabelText('Task URL')).toHaveTextContent('?panel=ff-new&request=FF-101');
    expect(screen.getByLabelText('Editing request')).toHaveTextContent('FF-101');
    fireEvent.click(screen.getByRole('button', { name: 'Open students' }));
    expect(screen.getByLabelText('Task URL')).toHaveTextContent('?panel=students');
    expect(screen.getByLabelText('Task URL')).not.toHaveTextContent('request=');
    expect(screen.getByLabelText('Editing request')).toBeEmptyDOMElement();
  });

  it('waits for entitlements before replacing a bookmarked task and keeps unrelated URL parameters', async () => {
    const { rerender } = render(<MemoryRouter initialEntries={['/dashboard?panel=students&source=bookmark']}><Navigation modules={null} /></MemoryRouter>);
    expect(screen.getByLabelText('Entitlements ready')).toHaveTextContent('false');
    expect(screen.getByLabelText('Task URL')).toHaveTextContent('panel=students');
    rerender(<MemoryRouter initialEntries={['/dashboard?panel=students&source=bookmark']}><Navigation modules={new Set()} /></MemoryRouter>);
    await waitFor(() => expect(screen.getByLabelText('Task URL')).toHaveTextContent('panel=home'));
    expect(screen.getByLabelText('Task URL')).toHaveTextContent('source=bookmark');
  });
});
