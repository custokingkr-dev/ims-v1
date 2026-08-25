import { Eye, LoaderCircle, Pencil } from 'lucide-react';
import {
  FILTERS,
  type EditingState,
  type ImportBatch,
  type ImportRow,
  type RowFilter,
  editingStateFor,
  statusTone,
} from './model';

interface Props {
  batch: ImportBatch;
  rows: ImportRow[];
  visibleRows: ImportRow[];
  filter: RowFilter;
  busy: string;
  onFilterChange: (filter: RowFilter) => void;
  onOpenPreview: (row: ImportRow) => void;
  onEdit: (editing: EditingState) => void;
}

export function PhotoImportReviewTable({
  batch,
  rows,
  visibleRows,
  filter,
  busy,
  onFilterChange,
  onOpenPreview,
  onEdit,
}: Props) {
  if (rows.length === 0) return null;

  return (
    <section className="pi-review">
      <div className="pi-review-toolbar">
        <div>
          <h2>Mapping review</h2>
          <p>{visibleRows.length} of {rows.length} rows</p>
        </div>
        <div className="pi-segments" aria-label="Row status filter">
          {FILTERS.map(value => (
            <button
              key={value}
              className={filter === value ? 'active' : ''}
              aria-pressed={filter === value}
              onClick={() => onFilterChange(value)}
            >
              {value}
            </button>
          ))}
        </div>
      </div>
      <div className="pi-table-wrap">
        <table className="pi-table">
          <thead>
            <tr>
              <th>Row</th>
              <th>Student</th>
              <th>Class</th>
              <th>Image mapping</th>
              <th>Status</th>
              <th aria-label="Preview" />
            </tr>
          </thead>
          <tbody>
            {visibleRows.length === 0 ? (
              <tr>
                <td colSpan={6}>
                  <div className="pi-table-empty" role="status">
                    <strong>No {filter.toLowerCase()} rows</strong>
                    <span>This batch has no rows matching the selected status.</span>
                    <button type="button" className="ck-btn ck-btn-ghost" onClick={() => onFilterChange('ALL')}>
                      Show all rows
                    </button>
                  </div>
                </td>
              </tr>
            ) : visibleRows.map(row => (
              <tr key={row.id}>
                <td>{row.excelRow}</td>
                <td>
                  <strong>{row.workbookName || '-'}</strong>
                  <span>Admission {row.admissionNo || '-'}</span>
                </td>
                <td>{row.className || '-'} / {row.sectionName || '-'}</td>
                <td>
                  <strong>{row.imageNo ? `Image ${row.imageNo}` : 'No image number'}</strong>
                  <span>{row.driveFileName || row.message || 'Not mapped'}</span>
                </td>
                <td>
                  <span className={`pi-status ${statusTone(row.status)}`}>{row.status}</span>
                  {row.message && <small>{row.message}</small>}
                </td>
                <td>
                  <div className="pi-row-actions">
                    <button
                      className="pi-icon-button"
                      aria-label={`Preview portrait for ${row.workbookName}`}
                      title="Preview preserved photo"
                      disabled={!row.driveFileName || busy === `preview:${row.id}`}
                      onClick={() => onOpenPreview(row)}
                    >
                      {busy === `preview:${row.id}`
                        ? <LoaderCircle className="pi-spin" size={16} aria-hidden />
                        : <Eye size={16} aria-hidden />}
                    </button>
                    {batch.status === 'REVIEW' && (
                      <button
                        className="pi-icon-button"
                        aria-label={`Review mapping for ${row.workbookName}`}
                        title="Review mapping"
                        onClick={() => onEdit(editingStateFor(row))}
                        disabled={!!busy}
                      >
                        <Pencil size={16} aria-hidden />
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
