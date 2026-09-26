import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { Stat } from './ui';

describe('Stat', () => {
  afterEach(cleanup);

  it('is not a control when there is nothing to do with it', () => {
    // Every one of the portal's stat cards rendered as a <button> with no handler, so keyboard
    // users tabbed through a row of controls that did nothing and led nowhere.
    render(<Stat label="Fee defaulters" value={4} sub="Students with overdue fees" tone="red" />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByText('Fee defaulters')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
  });

  it('is still a control when it does something', () => {
    const onClick = vi.fn();
    render(<Stat label="Pending" value={2} sub="Awaiting payment" tone="orange" onClick={onClick} />);
    const button = screen.getByRole('button', { name: /Pending/ });
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledOnce();
  });
});
