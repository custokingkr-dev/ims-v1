import { render, screen, waitFor, within, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StudentsPanel } from './StudentsPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { branchId: 7 } }) }));
vi.mock('../../../hooks/usePermissions', () => ({
  usePermissions: () => ({ can: (permission: string) => permission !== 'platform:admin' }),
}));

describe('StudentsPanel', () => {
  afterEach(cleanup);

  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/students') {
        return Promise.resolve({
          data: {
            items: [{
              id: 101,
              fullName: 'Aarav Sharma',
              admissionNumber: 'ADM-101',
              rollNo: '7',
              classSection: 'Class 2 - A',
              academicYear: '2026-27',
              fatherName: 'Ravi Sharma',
              fatherContact: '9876543210',
              feeStatus: 'Pending',
              attendancePercent: 92,
            }],
            filteredCount: 1,
            filteredSections: 1,
            totalPages: 1,
            filters: { classes: ['Class 2'], sections: ['A'], feeStatuses: ['Pending'] },
          },
        });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('shows father contact only in the father contact column', async () => {
    render(<StudentsPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);

    const studentName = await screen.findByText('Aarav Sharma');
    const row = studentName.closest('tr');
    expect(row).not.toBeNull();

    await waitFor(() => expect(api.get).toHaveBeenCalledWith(
      '/students',
      { params: { page: 0, size: 50, schoolId: 7 } },
    ));
    const cells = within(row as HTMLTableRowElement).getAllByRole('cell');
    expect(cells[2]).toHaveTextContent('Ravi Sharma');
    expect(cells[2]).not.toHaveTextContent('9876543210');
    expect(cells[3]).toHaveTextContent('9876543210');
  });
});
