import { useEffect, useRef, type Dispatch, type SetStateAction } from 'react';
import { Ban, Check, Eye, LoaderCircle, XCircle } from 'lucide-react';
import type { EditingState, PreviewState } from './model';

interface Props {
  preview: PreviewState | null;
  editing: EditingState | null;
  busy: string;
  onClosePreview: () => void;
  onEditingChange: Dispatch<SetStateAction<EditingState | null>>;
  onSaveReview: (previewAfterSave: boolean) => void;
}

export function PhotoImportDialogs({
  preview,
  editing,
  busy,
  onClosePreview,
  onEditingChange,
  onSaveReview,
}: Props) {
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!preview && !editing) return undefined;
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialogRef.current?.focus();
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      if (preview) onClosePreview();
      else onEditingChange(null);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => {
      window.removeEventListener('keydown', closeOnEscape);
      previouslyFocused?.focus();
    };
  }, [editing, onClosePreview, onEditingChange, preview]);

  return (
    <>
      {preview && (
        <div className="pi-preview-backdrop" role="presentation" onMouseDown={onClosePreview}>
          <div
            className="pi-preview-dialog"
            ref={dialogRef}
            role="dialog"
            aria-modal="true"
            aria-label="Full-frame photo preview"
            tabIndex={-1}
            onMouseDown={event => event.stopPropagation()}
          >
            <div>
              <strong>{preview.row.workbookName}</strong>
              <span>Admission {preview.row.admissionNo} / {preview.row.driveFileName}</span>
            </div>
            <img src={preview.url} alt={`Full-frame photo for ${preview.row.workbookName}`} />
            <button type="button" className="ck-btn ck-btn-ghost" onClick={onClosePreview}>Close</button>
          </div>
        </div>
      )}

      {editing && (
        <div className="pi-preview-backdrop" role="presentation" onMouseDown={() => onEditingChange(null)}>
          <div
            className="pi-review-dialog"
            ref={dialogRef}
            role="dialog"
            aria-modal="true"
            aria-label="Review photo mapping"
            tabIndex={-1}
            onMouseDown={event => event.stopPropagation()}
          >
            <div className="pi-dialog-head">
              <div>
                <strong>{editing.row.workbookName || 'Workbook row'}</strong>
                <span>Excel row {editing.row.excelRow}</span>
              </div>
              <button type="button" className="pi-icon-button" aria-label="Close review" onClick={() => onEditingChange(null)}>
                <XCircle size={16} aria-hidden />
              </button>
            </div>
            <div className="pi-review-fields">
              <label>
                <span>Admission number</span>
                <input
                  type="text"
                  value={editing.admissionNo}
                  disabled={editing.excluded}
                  onChange={event => onEditingChange(current => current && ({ ...current, admissionNo: event.target.value }))}
                />
              </label>
              <label>
                <span>Image number</span>
                <input
                  type="text"
                  value={editing.imageNo}
                  disabled={editing.excluded}
                  onChange={event => onEditingChange(current => current && ({ ...current, imageNo: event.target.value }))}
                />
              </label>
            </div>
            <label className="pi-exclude-control">
              <input
                type="checkbox"
                checked={editing.excluded}
                onChange={event => onEditingChange(current => current && ({ ...current, excluded: event.target.checked }))}
              />
              <Ban size={16} aria-hidden />
              <span>Exclude this row from the import</span>
            </label>
            <div className="ts">The complete source frame is preserved; no automatic crop is applied.</div>
            <div className="pi-dialog-actions">
              <button type="button" className="ck-btn ck-btn-ghost" onClick={() => onEditingChange(null)} disabled={!!busy}>Cancel</button>
              <button
                type="button"
                className="ck-btn ck-btn-ghost"
                onClick={() => onSaveReview(true)}
                disabled={!!busy || editing.excluded || !editing.imageNo.trim()}
              >
                <Eye size={16} aria-hidden /> Save and preview
              </button>
              <button type="button" className="ck-btn ck-btn-g" onClick={() => onSaveReview(false)} disabled={!!busy}>
                {busy === `edit:${editing.row.id}`
                  ? <LoaderCircle className="pi-spin" size={16} aria-hidden />
                  : <Check size={16} aria-hidden />}
                Save review
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
