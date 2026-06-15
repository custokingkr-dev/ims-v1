import { useEffect, useState } from 'react';
import { useDebounce } from '../hooks/useDebounce';

interface SearchInputProps {
  /** Placeholder text shown when the input is empty */
  placeholder?: string;
  /** Called with the debounced value after the user stops typing */
  onSearch: (query: string) => void;
  /** Debounce delay in milliseconds — defaults to 300 */
  debounceMs?: number;
  /** Initial value — useful when restoring from URL state */
  defaultValue?: string;
  /** Optional extra CSS class on the outer wrapper */
  className?: string;
  /** Accessible label for the input — defaults to "Search" */
  ariaLabel?: string;
}

/**
 * Controlled search input with built-in debounce.
 *
 * The `onSearch` callback is only fired after the user has stopped typing
 * for {@link debounceMs} milliseconds, preventing unnecessary API calls.
 *
 * The input maintains its own raw state so the UI remains responsive while
 * the debounce window is open.
 *
 * Uses `ck-search-input` tokens — no new CSS needed.
 *
 * @example
 * <SearchInput
 *   placeholder="Search students…"
 *   onSearch={q => fetchStudents(q)}
 * />
 */
export function SearchInput({
  placeholder = 'Search…',
  onSearch,
  debounceMs = 300,
  defaultValue = '',
  className = '',
  ariaLabel = 'Search',
}: SearchInputProps) {
  const [rawValue, setRawValue] = useState(defaultValue);
  const debouncedValue = useDebounce(rawValue, debounceMs);

  // Fire callback only when the debounced value changes
  useEffect(() => {
    onSearch(debouncedValue);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedValue]);

  return (
    <div className={`ck-search-input-wrapper ${className}`.trim()}>
      <span className="ck-search-input-icon" aria-hidden="true">🔍</span>
      <input
        type="search"
        className="ck-search-input"
        placeholder={placeholder}
        value={rawValue}
        aria-label={ariaLabel}
        onChange={e => setRawValue(e.target.value)}
      />
      {rawValue.length > 0 && (
        <button
          type="button"
          className="ck-search-input-clear ck-btn-ghost"
          aria-label="Clear search"
          onClick={() => setRawValue('')}
        >
          ×
        </button>
      )}
    </div>
  );
}
