import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PhotoImportDialogs } from './PhotoImportDialogs';
import type { EditingState, PreviewState } from './model';

const row = {
  id: 'row-1',
  excelRow: 2,
  admissionNo: 'ADM-1',
  workbookName: 'Student One',
  className: 'I',
  sectionName: 'A',
  imageNo: '5001',
  driveFileName: 'DSC5001.jpg',
  status: 'READY' as const,
  cropX: 0.5,
  cropY: 0.5,
  manuallyReviewed: false,
};

describe('PhotoImportDialogs', () => {
  it('closes a full-frame preview from the keyboard', () => {
    const onClosePreview = vi.fn();
    const preview: PreviewState = { row, url: 'blob:preview' };
    render(
      <PhotoImportDialogs
        preview={preview}
        editing={null}
        busy=""
        onClosePreview={onClosePreview}
        onEditingChange={vi.fn()}
        onSaveReview={vi.fn()}
      />,
    );

    expect(screen.getByRole('dialog', { name: /full-frame photo preview/i })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClosePreview).toHaveBeenCalledOnce();
  });

  it('prevents concurrent review actions while a save is running', () => {
    const editing: EditingState = {
      row,
      admissionNo: row.admissionNo,
      imageNo: row.imageNo,
      excluded: false,
      cropX: 0.5,
      cropY: 0.5,
    };
    render(
      <PhotoImportDialogs
        preview={null}
        editing={editing}
        busy="edit:row-1"
        onClosePreview={vi.fn()}
        onEditingChange={vi.fn()}
        onSaveReview={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: /save and preview/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /save review/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /cancel/i })).toBeDisabled();
  });
});
