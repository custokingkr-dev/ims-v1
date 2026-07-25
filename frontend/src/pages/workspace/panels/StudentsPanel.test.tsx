import { fireEvent, render, screen, waitFor, within, cleanup } from '@testing-library/react';
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
    vi.mocked(api.delete).mockReset();
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

  it('filters while typing without a Search button', async () => {
    render(<StudentsPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);

    await screen.findByText('Aarav Sharma');
    expect(screen.queryByRole('button', { name: /^search$/i })).not.toBeInTheDocument();

    fireEvent.change(screen.getByRole('textbox', { name: /search students by details/i }), {
      target: { value: 'Aarav' },
    });

    await waitFor(() => expect(api.get).toHaveBeenCalledWith(
      '/students',
      { params: { page: 0, size: 50, q: 'Aarav', schoolId: 7 } },
    ));
  });

  it('keeps current rows visible while search results are loading', async () => {
    render(<StudentsPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);
    await screen.findByText('Aarav Sharma');

    let resolveSearch!: (value: any) => void;
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/students') {
        return new Promise((resolve) => {
          resolveSearch = resolve;
        });
      }
      return Promise.resolve({ data: [] });
    });

    fireEvent.change(screen.getByRole('textbox', { name: /search students by details/i }), {
      target: { value: 'Mira' },
    });

    await waitFor(() => expect(api.get).toHaveBeenCalledWith(
      '/students',
      { params: { page: 0, size: 50, q: 'Mira', schoolId: 7 } },
    ));
    expect(screen.getByText('Aarav Sharma')).toBeInTheDocument();
    expect(document.querySelector('.ck-skeleton-row')).not.toBeInTheDocument();

    resolveSearch({
      data: {
        items: [],
        filteredCount: 0,
        filteredSections: 0,
        totalPages: 1,
        filters: { classes: [], sections: [], feeStatuses: [] },
      },
    });
    await screen.findByText('No students found for "Mira"');
  });

  it('requires admission number confirmation before deleting a student', async () => {
    vi.mocked(api.delete).mockResolvedValue({ data: { deleted: true } });
    render(<StudentsPanel setPanel={vi.fn()} onRefresh={vi.fn()} />);

    await screen.findByText('Aarav Sharma');
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));

    const dialog = screen.getByRole('dialog');
    const deleteButton = within(dialog).getByRole('button', { name: /delete student/i });
    expect(deleteButton).toBeDisabled();
    expect(api.delete).not.toHaveBeenCalled();

    fireEvent.change(within(dialog).getByPlaceholderText('ADM-101'), { target: { value: 'ADM-101' } });
    expect(deleteButton).not.toBeDisabled();
    fireEvent.click(deleteButton);

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith(
      '/students/101',
      { data: { reason: 'Deleted from Students tab', confirmationAdmissionNumber: 'ADM-101' } },
    ));
  });
});
