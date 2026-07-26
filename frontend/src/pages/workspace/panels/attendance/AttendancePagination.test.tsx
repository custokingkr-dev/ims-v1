import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AttendancePagination } from './AttendancePagination';

afterEach(cleanup);

describe('AttendancePagination', () => {
  it('reports the visible range and moves between pages', () => {
    const onPageChange = vi.fn();
    render(
      <AttendancePagination
        page={2}
        pageSize={10}
        totalItems={34}
        itemLabel="students"
        onPageChange={onPageChange}
        onPageSizeChange={vi.fn()}
      />
    );

    expect(screen.getByText('11-20 of 34 students')).toBeTruthy();
    expect(screen.getByText('Page 2 of 4')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(onPageChange).toHaveBeenCalledWith(3);
  });

  it('changes page size through the labelled control', () => {
    const onPageSizeChange = vi.fn();
    render(
      <AttendancePagination
        page={1}
        pageSize={10}
        totalItems={34}
        itemLabel="students"
        onPageChange={vi.fn()}
        onPageSizeChange={onPageSizeChange}
      />
    );

    fireEvent.change(screen.getByRole('combobox', { name: 'Rows per page' }), { target: { value: '20' } });
    expect(onPageSizeChange).toHaveBeenCalledWith(20);
  });
});
