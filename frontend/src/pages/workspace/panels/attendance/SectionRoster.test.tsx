import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { SectionRoster } from './SectionRoster';
import type { SectionRegisterResponse, StudentEditRecord } from '../../../../types/attendance';

afterEach(cleanup);

const register: SectionRegisterResponse = {
  date: '2024-03-04', classId: 'c1', sectionId: 's1', sectionName: 'Class 1-A',
  locked: false, totalStudents: 2, presentCount: 0, lateCount: 0, leaveCount: 0,
  absentCount: 0, presentPercent: 0,
  students: [
    { studentId: 1, fullName: 'A One', admissionNo: 'ADM1', rollNo: '1', photoUrl: null, status: null, remarks: '' },
    { studentId: 2, fullName: 'B Two', admissionNo: 'ADM2', rollNo: '2', photoUrl: null, status: null, remarks: '' },
  ],
};

function records(overrides: Partial<StudentEditRecord>[] = []): StudentEditRecord[] {
  const base: StudentEditRecord[] = [
    { studentId: 1, status: null, remarks: '' },
    { studentId: 2, status: null, remarks: '' },
  ];
  return base.map((r, i) => ({ ...r, ...(overrides[i] ?? {}) }));
}

describe('SectionRoster', () => {
  it('shows live summary counts from local records', () => {
    render(
      <SectionRoster register={register} records={records([{ status: 'PRESENT' }, { status: 'LATE' }])}
        loading={false} saving="" onStatusChange={vi.fn()} onRemarksChange={vi.fn()}
        onMarkAllPresent={vi.fn()} onReset={vi.fn()} onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    // Present cell value 1, Late cell value 1.
    const present = screen.getByText('Present').parentElement!;
    const late = screen.getByText('Late').parentElement!;
    expect(present.querySelector('.ck-att-summary-value')!.textContent).toBe('1');
    expect(late.querySelector('.ck-att-summary-value')!.textContent).toBe('1');
  });

  it('disables Submit until every student is marked, enables when all marked', () => {
    const { rerender } = render(
      <SectionRoster register={register} records={records([{ status: 'PRESENT' }])} loading={false} saving=""
        onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={vi.fn()} onReset={vi.fn()}
        onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    expect(screen.getByRole('button', { name: /Submit Section/ })).toBeDisabled();

    rerender(
      <SectionRoster register={register} records={records([{ status: 'PRESENT' }, { status: 'LEAVE' }])}
        loading={false} saving="" onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={vi.fn()}
        onReset={vi.fn()} onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    // Leave counts as marked -> all marked -> enabled.
    expect(screen.getByRole('button', { name: /Submit Section/ })).not.toBeDisabled();
  });

  it('fires callbacks for mark-all-present and save', () => {
    const onMarkAllPresent = vi.fn();
    const onSave = vi.fn();
    render(
      <SectionRoster register={register} records={records()} loading={false} saving=""
        onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={onMarkAllPresent}
        onReset={vi.fn()} onSave={onSave} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'Mark all Present' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onMarkAllPresent).toHaveBeenCalled();
    expect(onSave).toHaveBeenCalled();
  });
});
