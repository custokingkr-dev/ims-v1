import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import api from '../../../services/api';
import { PhotoImportPanel } from './PhotoImportPanel';

vi.mock('../../../services/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const context = {
  driveConfigured: true,
  schools: [{
    id: 7,
    schoolUid: '11111111-1111-4111-8111-111111111111',
    name: 'Green Valley School',
    shortCode: 'GVS',
    academicYearId: 'ay_2026_27',
    academicYearLabel: '2026-27',
  }],
  mappingColumns: ['AdmissionNo', 'Name', 'Class', 'Section', 'ImageNo'],
  fileNameRule: 'DSC5236.jpg or DSC_05236.JPG',
};

const frozenBatch = {
  id: 'batch-2',
  schoolId: 7,
  schoolName: 'Green Valley School',
  academicYearId: 'ay_2026_27',
  academicYearLabel: '2026-27',
  driveFolderId: 'folder-2',
  driveFolderName: 'Class I Photos',
  workbookFileName: 'adm_no_imag_no_mapping.xlsx',
  status: 'FROZEN',
  totalRows: 18,
  readyCount: 18,
  heldCount: 2,
  errorCount: 0,
  appliedCount: 0,
  failedCount: 0,
  createdAt: '2026-07-31T00:00:00Z',
};

describe('PhotoImportPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: context } as any;
      if (url === '/student-photo-imports') return { data: [] } as any;
      throw new Error(`unexpected GET ${url}`);
    });
  });

  it('binds the selected platform school and current academic year to the Drive folder', async () => {
    vi.mocked(api.post).mockResolvedValue({
      data: {
        id: 'batch-1',
        schoolId: 7,
        schoolName: 'Green Valley School',
        academicYearId: 'ay_2026_27',
        academicYearLabel: '2026-27',
        driveFolderId: 'folder-1',
        driveFolderName: 'Class I Photos',
        status: 'DRAFT',
        totalRows: 0,
        readyCount: 0,
        heldCount: 0,
        errorCount: 0,
        appliedCount: 0,
        failedCount: 0,
        createdAt: '2026-07-31T00:00:00Z',
      },
    } as any);

    render(<PhotoImportPanel />);
    expect(await screen.findByText('2026-27')).toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText('https://drive.google.com/drive/folders/...'), {
      target: { value: 'https://drive.google.com/drive/folders/1AbCdEfGhij_234567' },
    });
    fireEvent.click(screen.getByRole('button', { name: /verify and bind/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/student-photo-imports', {
      schoolId: 7,
      academicYearId: 'ay_2026_27',
      driveFolderUrl: 'https://drive.google.com/drive/folders/1AbCdEfGhij_234567',
    }));
    expect(await screen.findByRole('heading', { name: 'Green Valley School / 2026-27' }))
      .toBeInTheDocument();
  });

  it('requires scope confirmation and completes resumable execution chunks', async () => {
    let detailBatch = frozenBatch;
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: context } as any;
      if (url === '/student-photo-imports') return { data: [frozenBatch] } as any;
      if (url === '/student-photo-imports/batch-2') {
        return { data: { batch: detailBatch, rows: [] } } as any;
      }
      throw new Error(`unexpected GET ${url}`);
    });
    vi.mocked(api.post)
      .mockResolvedValueOnce({
        data: { ...frozenBatch, status: 'EXECUTING', readyCount: 8, appliedCount: 10 },
      } as any)
      .mockImplementationOnce(async () => {
        detailBatch = {
          ...frozenBatch,
          status: 'COMPLETED',
          readyCount: 0,
          appliedCount: 18,
        };
        return { data: detailBatch } as any;
      });

    render(<PhotoImportPanel />);
    fireEvent.click(await screen.findByRole('button', { name: /class i photos/i }));

    const execute = await screen.findByRole('button', { name: /execute import/i });
    expect(execute).toBeDisabled();
    fireEvent.click(screen.getByLabelText(/confirm green valley school, 2026-27, and 18 ready portraits/i));
    expect(execute).toBeEnabled();
    fireEvent.click(execute);

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('COMPLETED')).toBeInTheDocument();
  });
});
