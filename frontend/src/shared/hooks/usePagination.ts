import { useState } from 'react';

export interface PaginationState {
  /** Current 0-based page index */
  page: number;
  /** Number of items per page */
  pageSize: number;
}

export interface PaginationControls extends PaginationState {
  /** Total number of pages given the total item count */
  totalPages: number;
  /** Navigate to an explicit page index */
  goToPage: (page: number) => void;
  /** Jump to page 0 — also used to reset pagination after a filter change */
  goToFirst: () => void;
  /** Change the page size and reset to page 0 */
  setPageSize: (size: number) => void;
}

/**
 * Client-side pagination state manager.
 *
 * Use this to drive {@link Paginator} and slice any local list.  When the
 * backend returns a pre-paged response (e.g. Spring Data {@code Page<T>}),
 * pass `totalItems` from the response so `totalPages` stays accurate.
 *
 * @example
 * const pg = usePagination({ pageSize: 20 });
 * const slicedItems = allItems.slice(pg.page * pg.pageSize, (pg.page + 1) * pg.pageSize);
 *
 * // Reset to first page on filter change
 * const debouncedQuery = useDebounce(query, 300);
 * useEffect(() => { pg.goToFirst(); }, [debouncedQuery]);
 */
export function usePagination(
  opts: { initialPage?: number; pageSize?: number } = {},
): PaginationControls & { setTotalItems: (n: number) => void; totalItems: number } {
  const [page, setPage] = useState(opts.initialPage ?? 0);
  const [pageSize, setPageSizeState] = useState(opts.pageSize ?? 20);
  const [totalItems, setTotalItems] = useState(0);

  const totalPages = pageSize > 0 ? Math.max(1, Math.ceil(totalItems / pageSize)) : 1;

  function goToPage(p: number) {
    setPage(Math.max(0, Math.min(p, totalPages - 1)));
  }

  function goToFirst() {
    setPage(0);
  }

  function setPageSize(size: number) {
    setPageSizeState(size);
    setPage(0);
  }

  return {
    page,
    pageSize,
    totalPages,
    totalItems,
    goToPage,
    goToFirst,
    setPageSize,
    setTotalItems,
  };
}
