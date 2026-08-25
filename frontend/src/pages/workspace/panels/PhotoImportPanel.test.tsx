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
  managedDriveConfigured: true,
  schools: [{
    id: 7,
    schoolUid: '11111111-1111-4111-8111-111111111111',
    name: 'Green Valley School',
    shortCode: 'GVS',
    academicYearId: 'ay_2026_27',
    academicYearLabel: '2026-27',
    driveFolderStatus: 'READY',
    driveFolderId: 'folder-1',
    driveFolderName: 'Student Photo Intake',
    driveFolderUrl: 'https://drive.google.com/drive/folders/folder-1',
  }],
  mappingColumns: ['AdmissionNo', 'Name', 'Class', 'Section', 'ImageNo'],
  mappingFileFormats: ['XLSX', 'XLS', 'CSV', 'TSV'],
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

  it('starts a manual batch from the managed school and academic-year folder', async () => {
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
    expect(screen.getByText('GVS / 2026-27 / Student Photo Intake')).toBeInTheDocument();
    expect(screen.getByText(/XLSX, XLS, CSV, TSV/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /start manual import/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/student-photo-imports', {
      schoolId: 7,
      academicYearId: 'ay_2026_27',
    }, expect.objectContaining({ timeout: 120000 })));
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
    expect(screen.getByRole('progressbar', { name: /photo import complete/i }))
      .toHaveAttribute('aria-valuenow', '100');
  });

  it('pauses after the active execution chunk without scheduling another chunk', async () => {
    let detailBatch = frozenBatch;
    let resolveChunk: ((value: any) => void) | undefined;
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: context } as any;
      if (url === '/student-photo-imports') return { data: [detailBatch] } as any;
      if (url === '/student-photo-imports/batch-2') {
        return { data: { batch: detailBatch, rows: [], access: null } } as any;
      }
      throw new Error(`unexpected GET ${url}`);
    });
    vi.mocked(api.post).mockImplementationOnce(() => new Promise(resolve => {
      resolveChunk = resolve;
    }));

    render(<PhotoImportPanel />);
    fireEvent.click(await screen.findByRole('button', { name: /class i photos/i }));
    fireEvent.click(await screen.findByLabelText(/confirm green valley school, 2026-27, and 18 ready portraits/i));
    fireEvent.click(await screen.findByRole('button', { name: /execute import/i }));

    fireEvent.click(await screen.findByRole('button', { name: /pause after current chunk/i }));
    expect(screen.getByRole('button', { name: /pausing after current chunk/i })).toBeDisabled();

    detailBatch = {
      ...frozenBatch,
      status: 'EXECUTING',
      readyCount: 8,
      appliedCount: 10,
    };
    resolveChunk?.({ data: detailBatch });

    expect(await screen.findByText(/execution paused after the current chunk/i)).toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('progressbar', { name: /photo import paused/i }))
      .toHaveAttribute('aria-valuenow', '56');
    expect(screen.getByRole('button', { name: /resume import/i })).toBeDisabled();
    expect(screen.getByLabelText(/confirm green valley school, 2026-27, and 8 ready portraits/i))
      .not.toBeChecked();
  });

  it('continues execution after a transient timeout by refreshing batch progress', async () => {
    let detailBatch = frozenBatch;
    let detailCalls = 0;
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: context } as any;
      if (url === '/student-photo-imports') return { data: [detailBatch] } as any;
      if (url === '/student-photo-imports/batch-2') {
        detailCalls += 1;
        if (detailCalls >= 2 && detailBatch.status === 'FROZEN') {
          detailBatch = {
            ...frozenBatch,
            status: 'EXECUTING',
            readyCount: 16,
            appliedCount: 2,
          };
        }
        return { data: { batch: detailBatch, rows: [], access: null } } as any;
      }
      throw new Error(`unexpected GET ${url}`);
    });
    vi.mocked(api.post)
      .mockResolvedValueOnce({
        data: { ...frozenBatch, status: 'EXECUTING', readyCount: 17, appliedCount: 1 },
      } as any)
      .mockRejectedValueOnce({
        code: 'ECONNABORTED',
        message: 'timeout of 120000ms exceeded',
      })
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
    fireEvent.click(await screen.findByLabelText(/confirm green valley school, 2026-27, and 18 ready portraits/i));
    fireEvent.click(await screen.findByRole('button', { name: /execute import/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(3));
    expect((await screen.findAllByText('COMPLETED')).length).toBeGreaterThan(0);
  });

  it('keeps manual Drive binding available only while managed Drive is unconfigured', async () => {
    const unconfigured = {
      ...context,
      managedDriveConfigured: false,
      schools: [{
        ...context.schools[0],
        driveFolderStatus: 'NOT_PROVISIONED',
        driveFolderId: undefined,
        driveFolderName: undefined,
        driveFolderUrl: undefined,
      }],
    };
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: unconfigured } as any;
      if (url === '/student-photo-imports') return { data: [] } as any;
      throw new Error(`unexpected GET ${url}`);
    });
    vi.mocked(api.post).mockResolvedValue({
      data: {
        ...frozenBatch,
        id: 'batch-fallback',
        status: 'DRAFT',
        driveFolderId: 'manual-folder',
      },
    } as any);

    render(<PhotoImportPanel />);
    const input = await screen.findByPlaceholderText('https://drive.google.com/drive/folders/...');
    fireEvent.change(input, {
      target: { value: 'https://drive.google.com/drive/folders/manual-folder' },
    });
    fireEvent.click(screen.getByRole('button', { name: /verify and start/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/student-photo-imports', {
      schoolId: 7,
      academicYearId: 'ay_2026_27',
      driveFolderUrl: 'https://drive.google.com/drive/folders/manual-folder',
    }, expect.objectContaining({ timeout: 120000 })));
  });

  it('disambiguates duplicate school names in the selector', async () => {
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') {
        return {
          data: {
            ...context,
            schools: [
              context.schools[0],
              {
                ...context.schools[0],
                id: 9,
                schoolUid: '22222222-2222-4222-8222-222222222222',
                shortCode: 'GVS-N',
              },
            ],
          },
        } as any;
      }
      if (url === '/student-photo-imports') return { data: [] } as any;
      throw new Error(`unexpected GET ${url}`);
    });

    render(<PhotoImportPanel />);

    await screen.findByLabelText('School');
    const options = screen.getAllByRole('option').map(option => option.textContent);
    expect(options).toContain('Green Valley School (GVS, #7)');
    expect(options).toContain('Green Valley School (GVS-N, #9)');
  });

  it('shows problem-detail messages for forbidden import actions', async () => {
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: context } as any;
      if (url === '/student-photo-imports') return { data: [frozenBatch] } as any;
      if (url === '/student-photo-imports/batch-2') {
        return { data: { batch: frozenBatch, rows: [] } } as any;
      }
      throw new Error(`unexpected GET ${url}`);
    });
    vi.mocked(api.post).mockRejectedValue({
      response: {
        status: 403,
        data: { detail: 'permission required: student:photo-import' },
      },
      message: 'Request failed with status code 403',
    });

    render(<PhotoImportPanel />);
    fireEvent.click(await screen.findByRole('button', { name: /class i photos/i }));
    fireEvent.click(await screen.findByLabelText(/confirm green valley school, 2026-27, and 18 ready portraits/i));
    fireEvent.click(await screen.findByRole('button', { name: /execute import/i }));

    expect(await screen.findByText('permission required: student:photo-import')).toBeInTheDocument();
  });

  it('lets an operator correct a row mapping without destructive crop controls', async () => {
    const reviewBatch = { ...frozenBatch, id: 'batch-review', status: 'REVIEW', readyCount: 0, errorCount: 1 };
    const row = {
      id: 'row-1',
      excelRow: 2,
      admissionNo: 'BAD-ADM',
      workbookName: 'Student One',
      className: 'I',
      sectionName: 'A',
      imageNo: '5001',
      status: 'ERROR',
      message: 'No active student found',
      cropX: 0.5,
      cropY: 0.5,
      manuallyReviewed: false,
    };
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: context } as any;
      if (url === '/student-photo-imports') return { data: [reviewBatch] } as any;
      if (url === '/student-photo-imports/batch-review') {
        return {
          data: {
            batch: reviewBatch,
            rows: [row],
            access: { expiresAt: '2026-08-14T00:00:00Z', overdue: false },
          },
        } as any;
      }
      throw new Error(`unexpected GET ${url}`);
    });
    vi.mocked(api.post).mockResolvedValue({
      data: {
        batch: { ...reviewBatch, readyCount: 1, errorCount: 0 },
        row: {
          ...row,
          admissionNo: 'ADM-1',
          imageNo: '6001',
          driveFileName: 'DSC6001.jpg',
          status: 'READY',
          cropX: 0.25,
          cropY: 0.75,
          manuallyReviewed: true,
        },
      },
    } as any);

    render(<PhotoImportPanel />);
    fireEvent.click(await screen.findByRole('button', { name: /class i photos/i }));
    fireEvent.click(await screen.findByRole('button', { name: /review mapping for student one/i }));
    fireEvent.change(screen.getByLabelText('Admission number'), { target: { value: 'ADM-1' } });
    fireEvent.change(screen.getByLabelText('Image number'), { target: { value: '6001' } });
    expect(screen.queryByRole('slider')).not.toBeInTheDocument();
    expect(screen.getByText(/complete source frame is preserved/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /save review/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/student-photo-imports/batch-review/rows/row-1',
      {
        admissionNo: 'ADM-1',
        imageNo: '6001',
        excluded: false,
        cropX: 0.5,
        cropY: 0.5,
      },
    ));
    expect(await screen.findByText(/row review saved/i)).toBeInTheDocument();
  });

  it('restores applied photos in timeout-bounded chunks with current-photo protection', async () => {
    const appliedRows = Array.from({ length: 12 }, (_, index) => ({
      id: `${String(index + 1).padStart(8, '0')}-1111-4111-8111-111111111111`,
      excelRow: index + 2,
      admissionNo: `ADM-${index + 1}`,
      workbookName: `Student ${index + 1}`,
      className: 'I',
      sectionName: 'A',
      imageNo: String(5001 + index),
      driveFileName: `DSC${5001 + index}.jpg`,
      status: 'APPLIED',
      cropX: 0.5,
      cropY: 0.5,
      manuallyReviewed: false,
    }));
    const completedBatch = {
      ...frozenBatch,
      status: 'COMPLETED',
      readyCount: 0,
      appliedCount: appliedRows.length,
      totalRows: appliedRows.length,
    };
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: context } as any;
      if (url === '/student-photo-imports') return { data: [completedBatch] } as any;
      if (url === '/student-photo-imports/batch-2') {
        return { data: { batch: completedBatch, rows: appliedRows, access: null } } as any;
      }
      throw new Error(`unexpected GET ${url}`);
    });
    vi.mocked(api.post).mockImplementation(async (_url: string, requestBody?: unknown) => {
      const rowIds = (requestBody as { rowIds: string[] }).rowIds;
      return {
        data: {
          selectedCount: rowIds.length,
          recoveredCount: rowIds.length,
          alreadyRecoveredCount: 0,
          inProgressCount: 0,
          failedCount: 0,
          rows: rowIds.map(rowId => ({ rowId, status: 'RECOVERED' })),
        },
      } as any;
    });
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    render(<PhotoImportPanel />);
    fireEvent.click(await screen.findByRole('button', { name: /class i photos/i }));
    fireEvent.click(await screen.findByRole('button', { name: /restore full-frame photos/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(3));
    expect(api.post).toHaveBeenNthCalledWith(
      1,
      '/student-photo-imports/batch-2/recover',
      { rowIds: appliedRows.slice(0, 5).map(row => row.id) },
      expect.objectContaining({ timeout: 120000 }),
    );
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/student-photo-imports/batch-2/recover',
      { rowIds: appliedRows.slice(5, 10).map(row => row.id) },
      expect.objectContaining({ timeout: 120000 }),
    );
    expect(api.post).toHaveBeenNthCalledWith(
      3,
      '/student-photo-imports/batch-2/recover',
      { rowIds: appliedRows.slice(10).map(row => row.id) },
      expect.objectContaining({ timeout: 120000 }),
    );
    expect(await screen.findByText(/full-frame recovery completed for 12 photos/i)).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: /full-frame recovery complete/i }))
      .toHaveAttribute('aria-valuenow', '100');
    expect(screen.getByText(/12 of 12 reviewed/i)).toBeInTheDocument();
    expect(window.confirm).toHaveBeenCalledWith(expect.stringMatching(/changed after this import will be left untouched/i));
  });

  it('restores persisted recovery progress after refresh and labels protected photos separately', async () => {
    const appliedRow = {
      id: '00000001-1111-4111-8111-111111111111',
      excelRow: 2,
      admissionNo: 'ADM-1',
      workbookName: 'Student 1',
      className: 'I',
      sectionName: 'A',
      status: 'APPLIED',
      cropX: 0.5,
      cropY: 0.5,
      manuallyReviewed: false,
    };
    const completedBatch = {
      ...frozenBatch,
      status: 'COMPLETED',
      readyCount: 0,
      appliedCount: 12,
      totalRows: 12,
    };
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/student-photo-imports/context') return { data: context } as any;
      if (url === '/student-photo-imports') return { data: [completedBatch] } as any;
      if (url === '/student-photo-imports/batch-2') {
        return {
          data: {
            batch: completedBatch,
            rows: [appliedRow],
            access: null,
            recoveryProgress: {
              totalCount: 12,
              processedCount: 7,
              recoveredCount: 6,
              protectedCount: 1,
              failedCount: 0,
              inProgressCount: 0,
              pendingCount: 5,
              percentComplete: 58,
              resumable: true,
            },
          },
        } as any;
      }
      throw new Error(`unexpected GET ${url}`);
    });

    render(<PhotoImportPanel />);
    fireEvent.click(await screen.findByRole('button', { name: /class i photos/i }));

    expect(await screen.findByRole('button', { name: /resume full-frame recovery/i })).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: /full-frame recovery can resume/i }))
      .toHaveAttribute('aria-valuenow', '58');
    expect(screen.getByText(/6 restored, 1 protected, 0 failed, 5 pending/i)).toBeInTheDocument();
  });
});
