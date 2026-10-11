import { useEffect, useRef, useState } from 'react';

/**
 * Mirrors the drawer breakpoint in styles/sidebar.css. Below it the sidebar is
 * an off-canvas modal drawer over a backdrop; at or above it the very same
 * <aside> is a persistent, non-modal icon rail. Every modal behaviour below —
 * role="dialog", aria-modal, the focus trap, inerting the page behind it — is
 * gated on this, because applying any of it to the desktop rail would tell a
 * screen-reader user the whole workspace had disappeared behind a dialog.
 */
const DRAWER_BREAKPOINT = '(max-width: 768px)';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Focusable descendants that are actually rendered.
 *
 * The rendered filter is load-bearing rather than defensive: the drawer always
 * contains controls that CSS has removed at this breakpoint (.ck-sb-pin) or
 * that a collapsed accordion group hides (.ck-nav-item), and a trap whose
 * first/last handles point at a `display: none` button parks focus on nothing
 * at the wrap-around and silently drops the user back onto <body>.
 */
function focusableWithin(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    .filter((el) => el.getClientRects().length > 0);
}

/** Drawer accessibility and non-sensitive sidebar preferences. Draft state stays in WorkspaceDraftProvider. */
export function useWorkspaceDrawer(role: string | undefined) {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const sidebarRef = useRef<HTMLElement | null>(null);
  const mainRef = useRef<HTMLElement | null>(null);
  const menuToggleRef = useRef<HTMLButtonElement | null>(null);
  const sidebarCloseRef = useRef<HTMLButtonElement | null>(null);
  // matchMedia is guarded rather than assumed: jsdom (vitest) does not
  // implement it, and an unguarded call would take down every test that mounts
  // the workspace rather than just the drawer behaviour.
  const [isDrawerViewport, setIsDrawerViewport] = useState(
    () => (typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia(DRAWER_BREAKPOINT).matches
      : false),
  );
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(DRAWER_BREAKPOINT);
    const sync = (event: MediaQueryList | MediaQueryListEvent) => setIsDrawerViewport(event.matches);
    sync(query);
    query.addEventListener('change', sync);
    return () => query.removeEventListener('change', sync);
  }, []);
  /** The sidebar is a modal drawer only while it is open AND below the breakpoint. */
  const drawerIsModal = sidebarOpen && isDrawerViewport;

  // ── Modal drawer focus management ──────────────────────────────────────────
  // Opening the drawer used to leave focus on .ck-menu-toggle, so a keyboard
  // user had to tab through the entire (still interactive) page behind the
  // backdrop to reach the nav they had just opened.
  useEffect(() => {
    if (!drawerIsModal) return;
    const drawer = sidebarRef.current;
    if (!drawer) return;
    const main = mainRef.current;

    // Nothing behind the backdrop is reachable while the drawer is open.
    // Applied as a raw attribute because React 18's JSX types do not know
    // `inert`; the DOM property landed in React 19.
    main?.setAttribute('inert', '');
    (sidebarCloseRef.current ?? drawer).focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setSidebarOpen(false);
        return;
      }
      if (event.key !== 'Tab') return;
      const focusables = focusableWithin(drawer);
      if (focusables.length === 0) {
        event.preventDefault();
        return;
      }
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement as HTMLElement | null;
      const inside = !!active && drawer.contains(active);
      if (event.shiftKey) {
        if (!inside || active === first) {
          event.preventDefault();
          last.focus();
        }
      } else if (!inside || active === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      main?.removeAttribute('inert');
      // Hand focus back to whatever opened the drawer. Skipped when the drawer
      // stopped being modal because the viewport grew past the breakpoint — the
      // toggle is display:none up there and focusing it would silently dump
      // focus on <body>.
      const toggle = menuToggleRef.current;
      const active = document.activeElement;
      const focusIsLoose = !active || active === document.body || drawer.contains(active);
      if (toggle && toggle.getClientRects().length > 0 && focusIsLoose) toggle.focus();
    };
  }, [drawerIsModal]);

  const [pinned, setPinned] = useState(() => {
    try { return localStorage.getItem('ck_nav_pinned') !== '0'; } catch { return true; }
  });
  const togglePinned = () => {
    setPinned((prev) => {
      const next = !prev;
      try { localStorage.setItem('ck_nav_pinned', next ? '1' : '0'); } catch { /* ignore */ }
      return next;
    });
  };
  const navGroupsKey = `ck_nav_groups:${role ?? 'default'}`;
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() => {
    try { return JSON.parse(localStorage.getItem(navGroupsKey) || '{}'); } catch { return {}; }
  });
  const toggleGroup = (title: string) => {
    setOpenGroups((prev) => {
      const next = { ...prev, [title]: !(prev[title] ?? true) };
      try { localStorage.setItem(navGroupsKey, JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  };

  return {
    sidebarOpen, setSidebarOpen, drawerIsModal,
    sidebarRef, mainRef, menuToggleRef, sidebarCloseRef,
    pinned, togglePinned, openGroups, toggleGroup,
  };
}
