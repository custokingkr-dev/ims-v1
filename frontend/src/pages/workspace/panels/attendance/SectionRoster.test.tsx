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
        loading={false} saving="" readOnly={false} onStatusChange={vi.fn()} onRemarksChange={vi.fn()}
        onMarkAllPresent={vi.fn()} onMarkUnmarkedAbsent={vi.fn()} onReset={vi.fn()} onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    expect(screen.getByText(/P 1 · L 1 · Ex 0 · A 0/)).toBeTruthy();
  });

  it('disables Submit until every student is marked, enables when all marked', () => {
    const { rerender } = render(
      <SectionRoster register={register} records={records([{ status: 'PRESENT' }])} loading={false} saving=""
        readOnly={false} onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={vi.fn()} onMarkUnmarkedAbsent={vi.fn()} onReset={vi.fn()}
        onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    expect(screen.getByRole('button', { name: /Submit Section/ })).toBeDisabled();

    rerender(
      <SectionRoster register={register} records={records([{ status: 'PRESENT' }, { status: 'LEAVE' }])}
        loading={false} saving="" readOnly={false} onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={vi.fn()}
        onMarkUnmarkedAbsent={vi.fn()} onReset={vi.fn()} onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    // Leave counts as marked -> all marked -> enabled.
    expect(screen.getByRole('button', { name: /Submit Section/ })).not.toBeDisabled();
  });

  it('fires callbacks for mark-all-present and save', () => {
    const onMarkAllPresent = vi.fn();
    const onSave = vi.fn();
    render(
      <SectionRoster register={register} records={records()} loading={false} saving=""
        readOnly={false} onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={onMarkAllPresent}
        onMarkUnmarkedAbsent={vi.fn()} onReset={vi.fn()} onSave={onSave} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'Mark all Present' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onMarkAllPresent).toHaveBeenCalled();
    expect(onSave).toHaveBeenCalled();
  });

  it('paginates a long roster without changing the table height contract', () => {
    const longRegister: SectionRegisterResponse = {
      ...register,
      totalStudents: 12,
      students: Array.from({ length: 12 }, (_, index) => ({
        studentId: index + 1,
        fullName: `Student ${index + 1}`,
        admissionNo: `ADM${index + 1}`,
        rollNo: String(index + 1),
        photoUrl: null,
        status: null,
        remarks: '',
      })),
    };
    const longRecords: StudentEditRecord[] = longRegister.students.map((student) => ({
      studentId: student.studentId,
      status: null,
      remarks: '',
    }));
    render(
      <SectionRoster register={longRegister} records={longRecords} loading={false} saving=""
        readOnly={false} onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={vi.fn()}
        onMarkUnmarkedAbsent={vi.fn()} onReset={vi.fn()} onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );

    expect(screen.getByText('Student 1')).toBeTruthy();
    expect(screen.queryByText('Student 11')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText('Student 11')).toBeTruthy();
    expect(screen.getByText('11-12 of 12 students')).toBeTruthy();
  });

  it('bulk marks only selected students', () => {
    const onStatusChange = vi.fn();
    render(
      <SectionRoster register={register} records={records()} loading={false} saving=""
        readOnly={false} onStatusChange={onStatusChange} onRemarksChange={vi.fn()} onMarkAllPresent={vi.fn()}
        onMarkUnmarkedAbsent={vi.fn()} onReset={vi.fn()} onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select A One' }));
    fireEvent.click(screen.getByRole('button', { name: 'Absent' }));
    expect(onStatusChange).toHaveBeenCalledTimes(1);
    expect(onStatusChange).toHaveBeenCalledWith(1, 'ABSENT');
  });
});
