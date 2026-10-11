import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useWorkspaceDrawer } from './useWorkspaceDrawer';

let mobile = true;
const listeners = new Set<(event: { matches: boolean }) => void>();
beforeEach(() => {
  mobile = true;
  listeners.clear();
  localStorage.clear();
  vi.stubGlobal('matchMedia', () => ({
    get matches() { return mobile; },
    addEventListener: (_: string, listener: (event: { matches: boolean }) => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: (event: { matches: boolean }) => void) => listeners.delete(listener),
  }));
  // jsdom has no layout; model rendered controls for the focus trap.
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function Drawer() {
  const state = useWorkspaceDrawer('ADMIN');
  return <>
    <button ref={state.menuToggleRef} onClick={() => state.setSidebarOpen(true)}>Open menu</button>
    <aside ref={state.sidebarRef} role={state.drawerIsModal ? 'dialog' : undefined}>
      <button ref={state.sidebarCloseRef} onClick={() => state.setSidebarOpen(false)}>Close menu</button>
      <button onClick={state.togglePinned}>{state.pinned ? 'Unpin' : 'Pin'}</button>
      <button aria-expanded={state.openGroups.Students ?? true} onClick={() => state.toggleGroup('Students')}>Students group</button>
    </aside>
    <main ref={state.mainRef}><button>Underlying form</button></main>
  </>;
}

describe('workspace drawer lifecycle', () => {
  it('traps mobile focus, closes on Escape and restores focus and main content', () => {
    render(<Drawer />);
    fireEvent.click(screen.getByRole('button', { name: 'Open menu' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('inert');
    const close = screen.getByRole('button', { name: 'Close menu' });
    const last = screen.getByRole('button', { name: 'Students group' });
    expect(close).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(close).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('main')).not.toHaveAttribute('inert');
    expect(screen.getByRole('button', { name: 'Open menu' })).toHaveFocus();
  });

  it('only applies modal behavior below the breakpoint and cleans up listeners', () => {
    mobile = false;
    const { unmount } = render(<Drawer />);
    fireEvent.click(screen.getByRole('button', { name: 'Open menu' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('main')).not.toHaveAttribute('inert');
    act(() => { mobile = true; listeners.forEach(listener => listener({ matches: true })); });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('inert');
    act(() => { mobile = false; listeners.forEach(listener => listener({ matches: false })); });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('main')).not.toHaveAttribute('inert');
    unmount();
    expect(listeners.size).toBe(0);
  });

  it('restores sidebar pin and group preferences after remount', () => {
    const { unmount } = render(<Drawer />);
    fireEvent.click(screen.getByRole('button', { name: 'Unpin' }));
    fireEvent.click(screen.getByRole('button', { name: 'Students group' }));
    unmount();
    render(<Drawer />);
    expect(screen.getByRole('button', { name: 'Pin' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Students group' })).toHaveAttribute('aria-expanded', 'false');
  });
});
