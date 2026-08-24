import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import api from '../../../services/api';
import { downloadStudentExport } from '../../../features/students';
import { StudentExportPanel } from './StudentExportPanel';

vi.mock('../../../services/api', () => ({
  default: { get: vi.fn() },
}));

vi.mock('../../../features/students', () => ({
  downloadStudentExport: vi.fn(),
}));

describe('StudentExportPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.get).mockResolvedValue({
      data: {
        schools: [
          { id: 7, name: 'Green Valley School', shortCode: 'GVS', studentCount: 1000, photoCount: 980 },
          { id: 8, name: 'Lake View School', shortCode: 'LVS', studentCount: 500, photoCount: 500 },
        ],
        fileNameRule: "Each exported photo is named with the student's admission number.",
        workbookFileName: 'Student-Details.xlsx',
      },
    } as any);
    vi.mocked(downloadStudentExport).mockResolvedValue('saved');
  });

  it('requires a school selection and downloads that school only', async () => {
    render(<StudentExportPanel />);

    const button = await screen.findByRole('button', { name: /download excel and all photos/i });
    expect(button).toBeDisabled();

    fireEvent.change(screen.getByLabelText('School'), { target: { value: '7' } });
    expect(screen.getByText('1,000')).toBeInTheDocument();
    expect(screen.getByText('980')).toBeInTheDocument();
    expect(screen.getByText('20')).toBeInTheDocument();
    fireEvent.click(button);

    await waitFor(() => expect(downloadStudentExport).toHaveBeenCalledWith(
      7,
      expect.stringMatching(/^gvs-student-details-and-photos-\d{4}-\d{2}-\d{2}\.zip$/),
    ));
    expect(await screen.findByText(/downloaded green valley school/i)).toBeInTheDocument();
  });
});
