import { useEffect, useRef, type RefObject } from 'react';

const dialogs: HTMLElement[] = [];
let baselineOverflow = '';
const focusableSelector = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/** One focus boundary for both drawers and modals, including nested dialogs. */
export function useDialogFocus(ref: RefObject<HTMLElement>, open: boolean, onClose: () => void, disabled = false) {
  const closeRef = useRef(onClose);
  const disabledRef = useRef(disabled);
  closeRef.current = onClose;
  disabledRef.current = disabled;
  useEffect(() => {
    const dialog = ref.current;
    if (!open || !dialog) return;
    const previous = document.activeElement as HTMLElement | null;
    if (dialogs.length === 0) baselineOverflow = document.body.style.overflow;
    dialogs.push(dialog);
    document.body.style.overflow = 'hidden';
    const controls = () => Array.from(dialog.querySelectorAll<HTMLElement>(focusableSelector))
      .filter(element => {
        if (element.matches(':disabled, input[type="hidden"]') || element.closest('[hidden], [inert], [aria-hidden="true"]')) return false;
        for (let ancestor: HTMLElement | null = element; ancestor; ancestor = ancestor.parentElement) {
          const style = getComputedStyle(ancestor);
          if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false;
          if (ancestor === dialog) break;
        }
        return true;
      });
    (controls()[0] ?? dialog).focus();
    const handleKey = (event: KeyboardEvent) => {
      if (dialogs[dialogs.length - 1] !== dialog) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        if (!disabledRef.current) closeRef.current();
      }
      if (event.key !== 'Tab') return;
      const elements = controls();
      const first = elements[0] ?? dialog;
      const last = elements[elements.length - 1] ?? dialog;
      const active = document.activeElement;
      if (!dialog.contains(active) || (event.shiftKey ? active === first : active === last)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      }
    };
    const handleFocus = (event: FocusEvent) => {
      if (dialogs[dialogs.length - 1] === dialog && !dialog.contains(event.target as Node)) {
        (controls()[0] ?? dialog).focus();
      }
    };
    document.addEventListener('keydown', handleKey);
    document.addEventListener('focusin', handleFocus);
    return () => {
      document.removeEventListener('keydown', handleKey);
      document.removeEventListener('focusin', handleFocus);
      const index = dialogs.indexOf(dialog);
      if (index !== -1) dialogs.splice(index, 1);
      document.body.style.overflow = dialogs.length ? 'hidden' : baselineOverflow;
      if (previous?.isConnected) previous.focus();
    };
  }, [open, ref]);
}
