import { StrictMode, useState } from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkspaceDraftProvider, useWorkspaceDraft, useDraftDirty } from './WorkspaceDrafts';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function DraftForm() {
  const [value, setValue] = useWorkspaceDraft('example', '');
  const clearDirty = useDraftDirty('example', !!value);
  return <><input aria-label="Draft name" value={value} onChange={event => setValue(event.target.value)} /><button onClick={() => { clearDirty(); setValue(''); }}>Save draft</button></>;
}
function Workspace() {
  const [show, setShow] = useState(true);
  return <WorkspaceDraftProvider><button onClick={() => setShow(value => !value)}>Switch panel</button>{show && <DraftForm />}</WorkspaceDraftProvider>;
}
describe('workspace draft lifecycle', () => {
  it('retains unfinished work across panels without browser storage and warns before refresh', () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    const { unmount } = render(<Workspace />);
    fireEvent.change(screen.getByLabelText('Draft name'), { target: { value: 'Private student name' } });
    fireEvent.click(screen.getByRole('button', { name: 'Switch panel' }));
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Switch panel' }));
    expect(screen.getByLabelText('Draft name')).toHaveValue('Private student name');
    expect(storage).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    const savedEvent = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(savedEvent);
    expect(savedEvent.defaultPrevented).toBe(false);
    unmount();
    render(<Workspace />);
    expect(screen.getByLabelText('Draft name')).toHaveValue('');
  });

  it('delivers a pending operation result to a remounted panel under StrictMode', () => {
    let complete: ((value: string) => void) | undefined;
    function PendingOperation() {
      const [status, setStatus] = useWorkspaceDraft('pending', 'Saving');
      complete ??= setStatus;
      return <p>{status}</p>;
    }
    const { rerender } = render(<StrictMode><WorkspaceDraftProvider><PendingOperation /></WorkspaceDraftProvider></StrictMode>);
    rerender(<StrictMode><WorkspaceDraftProvider><p>Another panel</p></WorkspaceDraftProvider></StrictMode>);
    rerender(<StrictMode><WorkspaceDraftProvider><PendingOperation /></WorkspaceDraftProvider></StrictMode>);
    act(() => complete?.('Saved'));
    expect(screen.getByText('Saved')).toBeInTheDocument();
  });
});
