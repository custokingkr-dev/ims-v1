import type { ReactNode } from 'react';
import { EmptyState } from './EmptyState';

// ── Column definition ────────────────────────────────────────────────────────

export interface Column<T> {
  /** Column header label */
  header: string;
  /** Unique key used as the React `key` for the `<th>` / `<td>` */
  key: string;
  /**
   * Render function for the cell.  Receives the row datum and its 0-based
   * index.  Return a string, number, or any ReactNode.
   */
  render: (row: T, index: number) => ReactNode;
  /** Optional CSS class added to both `<th>` and `<td>` */
  className?: string;
}

// ── Props ────────────────────────────────────────────────────────────────────

interface DataTableProps<T> {
  /** Column definitions */
  columns: Column<T>[];
  /** Rows to display */
  rows: T[];
  /** Unique key extractor — defaults to `String(row[keyField])` */
  rowKey: (row: T) => string | number;
  /** Whether data is currently loading */
  loading?: boolean;
  /** Message shown when there are no rows and not loading */
  emptyMessage?: string;
  /** Secondary description shown below emptyMessage */
  emptyDescription?: string;
  /** Optional action button rendered inside the empty-state */
  emptyAction?: ReactNode;
  /** Optional CSS class added to the outer wrapper div */
  className?: string;
  /** Optional CSS class added to the `<table>` element */
  tableClassName?: string;
}

// ── Component ────────────────────────────────────────────────────────────────

/**
 * Generic sortable data table component.
 *
 * Handles loading, empty, and populated states.  All visual tokens come from
 * existing `ck-table` design tokens — no new CSS needed.
 *
 * @example
 * const columns: Column<Student>[] = [
 *   { key: 'name', header: 'Name', render: s => s.fullName },
 *   { key: 'fee', header: 'Fee Status', render: s => <StatusBadge status={s.feeStatus} /> },
 * ];
 *
 * <DataTable
 *   columns={columns}
 *   rows={students}
 *   rowKey={s => s.id}
 *   loading={isLoading}
 *   emptyMessage="No students found"
 * />
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  loading = false,
  emptyMessage = 'No data',
  emptyDescription,
  emptyAction,
  className = '',
  tableClassName = '',
}: DataTableProps<T>) {
  if (loading) {
    return (
      <div className={`ck-table-wrapper ${className}`.trim()}>
        <table className={`ck-table ${tableClassName}`.trim()}>
          <thead>
            <tr>
              {columns.map(col => (
                <th key={col.key} className={col.className ?? ''} scope="col">
                  {col.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {[1, 2, 3, 4, 5].map(i => (
              <tr key={i} style={{ animationDelay: `${(i - 1) * 60}ms` }}>
                {columns.map((col, j) => (
                  <td key={col.key} className={col.className ?? ''}>
                    <div
                      className="ck-skeleton ck-skeleton-text"
                      style={{ width: `${60 + ((i + j) % 4) * 10}%` }}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  if (rows.length === 0) {
    return (
      <div className={`ck-table-wrapper ${className}`.trim()}>
        <EmptyState
          message={emptyMessage}
          description={emptyDescription}
          action={emptyAction}
        />
      </div>
    );
  }

  return (
    <div className={`ck-table-wrapper ${className}`.trim()}>
      <table className={`ck-table ${tableClassName}`.trim()}>
        <thead>
          <tr>
            {columns.map(col => (
              <th key={col.key} className={col.className ?? ''} scope="col">
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, idx) => (
            <tr key={rowKey(row)}>
              {columns.map(col => (
                <td key={col.key} className={col.className ?? ''}>
                  {col.render(row, idx)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
