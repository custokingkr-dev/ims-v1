import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PhotoImportReviewTable } from './PhotoImportReviewTable';
import type { ImportBatch, ImportRow } from './model';

const batch: ImportBatch = {
  id: 'batch-review',
  schoolId: 7,
  schoolName: 'Green Valley School',
  academicYearId: 'ay-1',
  academicYearLabel: '2026-27',
  driveFolderId: 'folder-1',
  status: 'REVIEW',
  totalRows: 1,
  readyCount: 1,
  heldCount: 0,
  errorCount: 0,
  appliedCount: 0,
  failedCount: 0,
  createdAt: '2026-08-25T00:00:00Z',
};

const row: ImportRow = {
  id: 'row-1',
  excelRow: 2,
  admissionNo: 'ADM-1',
  workbookName: 'Student One',
  className: 'I',
  sectionName: 'A',
  imageNo: '5001',
  driveFileName: 'DSC5001.jpg',
  status: 'READY',
  cropX: 0.5,
  cropY: 0.5,
  manuallyReviewed: false,
};

describe('PhotoImportReviewTable', () => {
  it('offers recovery from an empty status filter without losing the batch rows', () => {
    const onFilterChange = vi.fn();
    render(
      <PhotoImportReviewTable
        batch={batch}
        rows={[row]}
        visibleRows={[]}
        filter="FAILED"
        busy=""
        onFilterChange={onFilterChange}
        onOpenPreview={vi.fn()}
        onEdit={vi.fn()}
      />,
    );

    expect(screen.getByRole('status')).toHaveTextContent(/no failed rows/i);
    expect(screen.getByRole('button', { name: /failed/i })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: /show all rows/i }));
    expect(onFilterChange).toHaveBeenCalledWith('ALL');
  });

  it('maps a review row into an editable state without changing crop defaults', () => {
    const onEdit = vi.fn();
    render(
      <PhotoImportReviewTable
        batch={batch}
        rows={[row]}
        visibleRows={[row]}
        filter="ALL"
        busy=""
        onFilterChange={vi.fn()}
        onOpenPreview={vi.fn()}
        onEdit={onEdit}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /review mapping for student one/i }));
    expect(onEdit).toHaveBeenCalledWith(expect.objectContaining({
      row,
      admissionNo: 'ADM-1',
      imageNo: '5001',
      cropX: 0.5,
      cropY: 0.5,
      excluded: false,
    }));
  });
});
