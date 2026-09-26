import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Modal } from './Modal';
import { CommandCenterDrawer } from '../pages/workspace/dashboard/components/CommandCenterDrawer';
import { Field } from '../pages/workspace/ui';

afterEach(cleanup);

describe('accessible dialog boundaries', () => {
  it('names the dialog, traps focus, handles Escape, and restores the opener', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    const close = vi.fn();
    const { unmount } = render(<Modal title="Edit school" subtitle="School details" onClose={close} footer={<button>Save</button>}><Field label="School name" hint="Use the official name"><input /></Field></Modal>);
    expect(screen.getByRole('dialog', { name: 'Edit school' })).toHaveAccessibleDescription('School details');
    expect(screen.getByLabelText('School name')).toHaveAccessibleDescription('Use the official name');
    expect(screen.getByRole('button', { name: 'Close Edit school' })).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(screen.getByRole('button', { name: 'Close Edit school' })).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(close).toHaveBeenCalledTimes(1);
    unmount();
    expect(opener).toHaveFocus();
    opener.remove();
  });

  it('keeps the parent drawer open when Escape closes its nested dialog', () => {
    const drawerClose = vi.fn();
    const modalClose = vi.fn();
    const { rerender } = render(<CommandCenterDrawer open title="Attendance" onClose={drawerClose}><button>Invite parents</button></CommandCenterDrawer>);
    screen.getByRole('button', { name: 'Invite parents' }).focus();
    rerender(<CommandCenterDrawer open title="Attendance" onClose={drawerClose}><button>Invite parents</button><Modal title="Invite parents" onClose={modalClose} footer={<button>Send</button>}>Message</Modal></CommandCenterDrawer>);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(modalClose).toHaveBeenCalledTimes(1);
    expect(drawerClose).not.toHaveBeenCalled();
  });

  it('skips hidden and disabled descendants and restores the scroll baseline after an outer-first unmount', () => {
    document.body.style.overflow = 'auto';
    const first = render(<Modal title="Outer" onClose={vi.fn()} disabled footer={<button>Last visible</button>}>
      <input type="hidden" />
      <div style={{ display: 'none' }}><button>Hidden ancestor</button></div>
      <button style={{ visibility: 'hidden' }}>Invisible</button>
      <fieldset disabled><button>Disabled by group</button></fieldset>
      <button>First visible</button>
    </Modal>);
    expect(screen.getByRole('button', { name: 'First visible' })).toHaveFocus();
    const second = render(<Modal title="Inner" onClose={vi.fn()} footer={<button>Finish</button>}>Inner content</Modal>);
    first.unmount();
    expect(document.body.style.overflow).toBe('hidden');
    second.unmount();
    expect(document.body.style.overflow).toBe('auto');
    document.body.style.overflow = '';
  });
});
