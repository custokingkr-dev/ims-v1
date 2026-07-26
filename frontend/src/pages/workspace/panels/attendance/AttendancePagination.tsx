import { ChevronLeft, ChevronRight } from 'lucide-react';

interface Props {
  page: number;
  pageSize: number;
  totalItems: number;
  pageSizeOptions?: number[];
  itemLabel: string;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
}

export function AttendancePagination({
  page,
  pageSize,
  totalItems,
  pageSizeOptions = [10, 20, 50],
  itemLabel,
  onPageChange,
  onPageSizeChange,
}: Props) {
  const pageCount = Math.max(1, Math.ceil(totalItems / pageSize));
  const safePage = Math.min(Math.max(1, page), pageCount);
  const start = totalItems === 0 ? 0 : (safePage - 1) * pageSize + 1;
  const end = Math.min(safePage * pageSize, totalItems);

  return (
    <div className="ck-att-pagination">
      <span className="ck-att-page-info">
        {totalItems === 0 ? `No ${itemLabel}` : `${start}-${end} of ${totalItems} ${itemLabel}`}
      </span>
      <div className="ck-att-page-controls">
        <button
          type="button"
          className="ck-att-icon-button"
          aria-label="Previous page"
          disabled={safePage === 1}
          onClick={() => onPageChange(safePage - 1)}
        >
          <ChevronLeft size={16} />
        </button>
        <span className="ck-att-page-number">Page {safePage} of {pageCount}</span>
        <button
          type="button"
          className="ck-att-icon-button"
          aria-label="Next page"
          disabled={safePage === pageCount}
          onClick={() => onPageChange(safePage + 1)}
        >
          <ChevronRight size={16} />
        </button>
      </div>
      <label className="ck-att-page-size">
        <span className="ck-sr-only">Rows per page</span>
        <select
          aria-label="Rows per page"
          value={pageSize}
          onChange={(event) => onPageSizeChange(Number(event.target.value))}
        >
          {pageSizeOptions.map((option) => (
            <option key={option} value={option}>{option} / page</option>
          ))}
        </select>
      </label>
    </div>
  );
}
