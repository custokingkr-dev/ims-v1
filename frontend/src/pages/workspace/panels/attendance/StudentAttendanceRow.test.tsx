import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { StudentAttendanceRow } from './StudentAttendanceRow';
import type { StudentAttendanceRecord } from '../../../../types/attendance';

afterEach(cleanup);

const student: StudentAttendanceRecord = {
  studentId: 1,
  fullName: 'Asha Rao',
  admissionNo: 'ADM1',
  rollNo: '1',
  photoUrl: null,
  status: null,
  remarks: '',
};

describe('StudentAttendanceRow', () => {
  it('sets the tapped status', () => {
    const onStatusChange = vi.fn();
    render(
      <StudentAttendanceRow student={student} status={null} remarks="" locked={false}
        onStatusChange={onStatusChange} onRemarksChange={vi.fn()} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'LATE' }));
    expect(onStatusChange).toHaveBeenCalledWith('LATE');
  });

  it('clears the status when the active pill is re-tapped', () => {
    const onStatusChange = vi.fn();
    render(
      <StudentAttendanceRow student={student} status="PRESENT" remarks="" locked={false}
        onStatusChange={onStatusChange} onRemarksChange={vi.fn()} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'PRESENT' }));
    expect(onStatusChange).toHaveBeenCalledWith(null);
  });

  it('renders read-only pills (no buttons) when locked', () => {
    render(
      <StudentAttendanceRow student={student} status="ABSENT" remarks="" locked
        onStatusChange={vi.fn()} onRemarksChange={vi.fn()} />
    );
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByPlaceholderText('Remarks')).toBeNull();
  });
});
