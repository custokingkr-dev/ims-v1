import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { withDerivedModuleGroups } from './config';
import { isItemOnlyOperator, useWorkspaceNavigation } from './useWorkspaceNavigation';

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

function NavItems({ granted, itemOnlyOperator }: { granted: string[]; itemOnlyOperator: boolean }) {
  const state = useWorkspaceNavigation({
    role: 'OPERATIONS', activeModules: withDerivedModuleGroups(['ORDERS', 'FIREFIGHTING']), itemOnlyOperator,
    can: code => granted.includes(code), canAny: codes => codes.some(code => granted.includes(code)),
  });
  const location = useLocation();
  return <>
    <output aria-label="Task URL">{location.search}</output>
    <ul aria-label="Navigation">{state.navSections.flatMap(section => section.items).map(item => <li key={item.key}>{item.label}</li>)}</ul>
  </>;
}
const navLabels = () => within(screen.getByRole('list', { name: 'Navigation' })).queryAllByRole('listitem').map(item => item.textContent);

describe('notebook order navigation', () => {
  // Exactly what identity grants an operator whose only assignment is one catalogue item at a school (owner decision D2).
  const itemOnly = ['workspace:access', 'notebook-tracker:read', 'notebook-tracker:act', 'order:read', 'order:update'];

  it('recognises an operator scoped only to catalogue items', () => {
    expect(isItemOnlyOperator({ role: 'OPERATIONS', operatorSchools: [], operatorScopes: ['7:NOTEBOOKS'] })).toBe(true);
    expect(isItemOnlyOperator({ role: 'OPERATIONS', operatorSchools: [7], operatorScopes: ['8:NOTEBOOKS'] })).toBe(false);
    expect(isItemOnlyOperator({ role: 'OPERATIONS', operatorSchools: [7], operatorScopes: [] })).toBe(false);
    expect(isItemOnlyOperator({ role: 'SUPERADMIN', operatorSchools: [], operatorScopes: ['7:NOTEBOOKS'] })).toBe(false);
    expect(isItemOnlyOperator(null)).toBe(false);
  });

  it('gives an item-only operator with no school the notebook orders and nothing else, and opens them', async () => {
    render(<MemoryRouter initialEntries={['/dashboard']}><NavItems granted={itemOnly} itemOnlyOperator /></MemoryRouter>);
    expect(navLabels()).toEqual(['Notebook orders']);
    await waitFor(() => expect(screen.getByLabelText('Task URL')).toHaveTextContent('?panel=nb-work'));
  });

  it('shows notebook orders to a school-wide operator who holds the tracker permission, beside their other work', () => {
    render(<MemoryRouter initialEntries={['/dashboard']}><NavItems granted={['notebook-tracker:read', 'order:read']} itemOnlyOperator={false} /></MemoryRouter>);
    expect(navLabels()).toEqual(expect.arrayContaining(['Supply Details', 'School Orders', 'Notebook orders']));
  });

  it('hides notebook orders from an operator without the tracker permission', () => {
    render(<MemoryRouter initialEntries={['/dashboard']}><NavItems granted={['workflow:read', 'order:read']} itemOnlyOperator={false} /></MemoryRouter>);
    expect(navLabels()).not.toContain('Notebook orders');
  });
});

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
