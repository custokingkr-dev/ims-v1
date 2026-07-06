# Design Language Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the *Slate & Signal · Dark Rail* design foundation — the token system (light+dark), self-hosted type, and the shared component kit + app shell — so every panel is built from one consistent, accessible system.

**Architecture:** Additive CSS custom-property token layer that redefines the existing `--ck-*` semantic vars in place (no panel markup changes required) and adds new tokens (rail, chart-dark, density, focus). A small set of token-driven React components under `frontend/src/components/ui/` plus two hooks (`useTheme`, `useDensity`). The workspace shell (`UnifiedWorkspacePage.tsx`) adopts a new `NavRail`, `Topbar`, and `CommandPalette` **visually only** — no panel-state logic moves.

**Tech Stack:** React 18, Vite 6, TypeScript 5.7, Vitest 3 + @testing-library/react, `@fontsource` (self-hosted IBM Plex, no CDN).

**Source spec:** `docs/superpowers/specs/2026-07-07-design-language-design.md`

## Global Constraints

- Colors authored to the spec's hex values verbatim; every text-on-surface pairing meets WCAG 2.2 AA (4.5:1 body, 3:1 large/UI). Copy hex exactly from spec §2.
- Chart palettes are the **validated** sets — do not hand-edit. Light: `#0072B2 #E69F00 #009E73 #CC79A7 #56B4E9 #D55E00`. Dark: `#2E86C4 #B57C0E #12A87E #C06B99 #3F9AD1 #DD6B3A`.
- Default UI text 14px; prose 16px; never below 12px. All numeric/ID elements use `font-variant-numeric: tabular-nums lining-nums`.
- Spacing strictly on the 8pt scale `4 8 12 16 24 32 48 64`. Radius `6 / 8 / 12 / 999`. Row density `32 / 40 / 48`, default 40.
- Depth via hairline borders + surface steps; shadows only on floating layers. `:focus-visible` = 2px `--accent` ring, ≥3:1, never removed.
- No new runtime dependency other than `@fontsource/*` (dev-time, self-hosted output). No TanStack/grid libs in this plan (deferred to module specs).
- `prefers-reduced-motion` respected on every transition; theme toggle overrides `prefers-color-scheme` both ways.
- Every task ends green: `cd frontend && npm test` and (final task) `npm run build` with 0 TS errors.

---

## File structure

**Create:**
- `frontend/src/styles/theme.css` — new token layer (primitives + semantic light + rail + chart + density + focus + motion), redefines `--ck-*`, keeps legacy shorthand aliases. Dark block included.
- `frontend/src/styles/ui-kit.css` — component classes for the new kit (rail, topbar, stat card, pill, button, side panel, command palette), all token-driven.
- `frontend/src/hooks/useTheme.ts`, `frontend/src/hooks/useTheme.test.ts`
- `frontend/src/hooks/useDensity.ts`, `frontend/src/hooks/useDensity.test.ts`
- `frontend/src/components/ui/StatusPill.tsx` (+ `.test.tsx`)
- `frontend/src/components/ui/StatCard.tsx` (+ `.test.tsx`)
- `frontend/src/components/ui/Button.tsx` (+ `.test.tsx`)
- `frontend/src/components/ui/SidePanel.tsx` (+ `.test.tsx`)
- `frontend/src/components/ui/CommandPalette.tsx`, `frontend/src/components/ui/fuzzy.ts` (+ `fuzzy.test.ts`, `CommandPalette.test.tsx`)
- `frontend/src/components/ui/NavRail.tsx` (+ `.test.tsx`)
- `frontend/src/components/ui/Topbar.tsx` (+ `.test.tsx`)

**Modify:**
- `frontend/src/main.tsx` — import fonts + `theme.css` + `ui-kit.css` (order matters).
- `frontend/src/pages/workspace/utils.ts` — add `formatDelta`.
- `frontend/src/pages/UnifiedWorkspacePage.tsx` — swap sidebar/topbar render for `NavRail`/`Topbar`, mount `CommandPalette` (visual only).
- `frontend/package.json` — add `@fontsource/ibm-plex-sans`, `@fontsource/ibm-plex-mono`.

**Reuse as-is (do not duplicate):** `shared/components/DataTable.tsx`, `shared/components/EmptyState.tsx`, `styles/skeleton.css`, `pages/workspace/utils.ts:formatMoney/formatLakh`.

---

## Phase A — Tokens, theme, fonts

### Task 1: New token layer (`theme.css`)

**Files:**
- Create: `frontend/src/styles/theme.css`
- Create: `frontend/src/styles/theme.test.ts`
- Modify: `frontend/src/main.tsx` (import order)

**Interfaces:**
- Produces: CSS custom properties on `:root` — `--bg-canvas --bg-surface --bg-raised --bg-overlay --text-primary --text-secondary --text-muted --border-subtle --border-default --accent --accent-ink --accent-soft --accent-on --rail-bg --rail-text --rail-text-muted --rail-text-faint --rail-active-bg --rail-active-text --rail-border --rail-badge-bg --rail-badge-text --ok --ok-soft --warn --warn-soft --danger --danger-soft --info --info-soft --chart-1..--chart-6 --space-1..--space-8 --r-sm --r-md --r-lg --r-pill --dur-fast --dur-base --dur-slow --ease --focus-ring`. Redefines `--ck-color-primary`, `--ck-bg-app`, `--ck-text-primary`, `--ck-border-subtle` to the new palette.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/styles/theme.test.ts
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';

const css = readFileSync(fileURLToPath(new URL('./theme.css', import.meta.url)), 'utf8');

describe('theme.css token layer', () => {
  it('defines the core semantic tokens with the spec hex values', () => {
    expect(css).toMatch(/--accent:\s*#4B57D2/i);
    expect(css).toMatch(/--rail-bg:\s*#161B2C/i);
    expect(css).toMatch(/--text-primary:\s*#14181F/i);
    expect(css).toMatch(/--bg-canvas:\s*#F6F7F9/i);
  });
  it('defines the six validated light chart tokens in order', () => {
    for (const hex of ['#0072B2', '#E69F00', '#009E73', '#CC79A7', '#56B4E9', '#D55E00']) {
      expect(css.toUpperCase()).toContain(hex);
    }
  });
  it('remaps the legacy --ck-* primary alias to the new accent', () => {
    expect(css).toMatch(/--ck-color-primary:\s*var\(--accent\)/);
  });
  it('respects reduced motion', () => {
    expect(css).toMatch(/prefers-reduced-motion/);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/styles/theme.test.ts`
Expected: FAIL — cannot find `./theme.css`.

- [ ] **Step 3: Write `theme.css`**

```css
/* frontend/src/styles/theme.css
   Slate & Signal · Dark Rail — design-language token layer.
   Additive: redefines the existing --ck-* semantic vars and legacy shorthand
   aliases to the new palette so panels inherit the new look with no markup change.
   Spec: docs/superpowers/specs/2026-07-07-design-language-design.md */

:root {
  /* ── Surfaces ── */
  --bg-canvas:#F6F7F9; --bg-surface:#FFFFFF; --bg-raised:#EFF1F5; --bg-overlay:rgba(20,24,31,.40);
  /* ── Text ── */
  --text-primary:#14181F; --text-secondary:#555D68; --text-muted:#88909B; --text-on-accent:#FFFFFF;
  /* ── Borders ── */
  --border-subtle:rgba(20,24,31,.085); --border-default:rgba(20,24,31,.14);
  /* ── Accent (indigo) ── */
  --accent:#4B57D2; --accent-ink:#3A46C0; --accent-soft:#EBEDFB; --accent-on:#FFFFFF;
  /* ── Rail (navy) ── */
  --rail-bg:#161B2C; --rail-text:#E8EAF4; --rail-text-muted:#98A0BD; --rail-text-faint:#7982A1;
  --rail-active-bg:rgba(99,110,230,.22); --rail-active-text:#BCC2F6; --rail-border:rgba(255,255,255,.08);
  --rail-badge-bg:rgba(255,255,255,.12); --rail-badge-text:#CDD2EA; --rail-badge-warn-bg:rgba(194,58,43,.22); --rail-badge-warn-text:#FFB3A0;
  /* ── Reserved status ── */
  --ok:#0F7A5A; --ok-soft:#E2F2EB; --warn:#A9760A; --warn-soft:#F5EDD9;
  --danger:#C23A2B; --danger-soft:#FBE7E4; --info:#1D6FD0; --info-soft:#E7F0FC;
  /* ── Validated chart palette (light) — fixed module→hue order ── */
  --chart-1:#0072B2; --chart-2:#E69F00; --chart-3:#009E73; --chart-4:#CC79A7; --chart-5:#56B4E9; --chart-6:#D55E00;
  /* ── Spacing (8pt) ── */
  --space-1:4px; --space-2:8px; --space-3:12px; --space-4:16px; --space-5:24px; --space-6:32px; --space-7:48px; --space-8:64px;
  /* ── Radius ── */
  --r-sm:6px; --r-md:8px; --r-lg:12px; --r-pill:999px;
  /* ── Elevation (floating only) ── */
  --shadow-popover:0 8px 24px rgba(20,24,31,.12), 0 2px 8px rgba(20,24,31,.06);
  --shadow-drawer:-4px 0 24px rgba(20,24,31,.10);
  /* ── Motion ── */
  --dur-fast:120ms; --dur-base:180ms; --dur-slow:300ms; --ease:cubic-bezier(.2,.8,.2,1);
  /* ── Focus ── */
  --focus-ring:0 0 0 2px var(--bg-surface), 0 0 0 4px var(--accent);
  /* ── Type ── */
  --font-ui:'IBM Plex Sans',-apple-system,'Segoe UI',system-ui,sans-serif;
  --font-mono:'IBM Plex Mono',ui-monospace,monospace;

  /* ── Redefine existing --ck-* semantic aliases onto the new palette ── */
  --ck-color-primary:var(--accent); --ck-color-primary-dark:var(--accent-ink); --ck-color-primary-soft:var(--accent-soft);
  --ck-bg-app:var(--bg-canvas); --ck-bg-surface:var(--bg-surface); --ck-bg-surface-raised:var(--bg-raised);
  --ck-text-primary:var(--text-primary); --ck-text-secondary:var(--text-secondary); --ck-text-muted:var(--text-muted);
  --ck-border-subtle:var(--border-subtle); --ck-border-default:var(--border-default);
  --ck-status-paid:var(--ok); --ck-status-paid-bg:var(--ok-soft);
  --ck-status-overdue:var(--danger); --ck-status-overdue-bg:var(--danger-soft);
  --ck-status-pending:var(--warn); --ck-status-pending-bg:var(--warn-soft);
  --ck-status-info:var(--info); --ck-status-info-bg:var(--info-soft);

  /* ── Legacy shorthand aliases (retired panel-by-panel later) ── */
  --g:var(--accent); --g1:var(--accent-soft); --b:var(--info); --b1:var(--info-soft);
  --am:var(--warn); --am1:var(--warn-soft); --re:var(--danger); --re1:var(--danger-soft);
  --bg:var(--bg-canvas); --white:var(--bg-surface); --ink:var(--text-primary); --ink2:var(--text-secondary); --ink3:var(--text-muted);
  --border:var(--border-subtle); --border2:var(--border-default);
}

/* ── Dark theme: same system, own steps (never pure black). Media default + explicit toggle. ── */
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { color-scheme: dark;
  --bg-canvas:#111318; --bg-surface:#181B21; --bg-raised:#1F232A; --bg-overlay:rgba(0,0,0,.55);
  --text-primary:#ECEEF2; --text-secondary:#A3A9B4; --text-muted:#6E7581;
  --border-subtle:rgba(255,255,255,.09); --border-default:rgba(255,255,255,.15);
  --accent:#7B84E8; --accent-ink:#AAB0F2; --accent-soft:rgba(123,132,232,.16);
  --rail-bg:#0F131E;
  --ok:#3FB488; --ok-soft:rgba(63,180,136,.16); --warn:#D3A24A; --warn-soft:rgba(211,162,74,.16);
  --danger:#E8705C; --danger-soft:rgba(232,112,92,.16); --info:#6EA8F0; --info-soft:rgba(110,168,240,.16);
  --chart-1:#2E86C4; --chart-2:#B57C0E; --chart-3:#12A87E; --chart-4:#C06B99; --chart-5:#3F9AD1; --chart-6:#DD6B3A;
} }
:root[data-theme="dark"] { color-scheme: dark;
  --bg-canvas:#111318; --bg-surface:#181B21; --bg-raised:#1F232A; --bg-overlay:rgba(0,0,0,.55);
  --text-primary:#ECEEF2; --text-secondary:#A3A9B4; --text-muted:#6E7581;
  --border-subtle:rgba(255,255,255,.09); --border-default:rgba(255,255,255,.15);
  --accent:#7B84E8; --accent-ink:#AAB0F2; --accent-soft:rgba(123,132,232,.16);
  --rail-bg:#0F131E;
  --ok:#3FB488; --ok-soft:rgba(63,180,136,.16); --warn:#D3A24A; --warn-soft:rgba(211,162,74,.16);
  --danger:#E8705C; --danger-soft:rgba(232,112,92,.16); --info:#6EA8F0; --info-soft:rgba(110,168,240,.16);
  --chart-1:#2E86C4; --chart-2:#B57C0E; --chart-3:#12A87E; --chart-4:#C06B99; --chart-5:#3F9AD1; --chart-6:#DD6B3A;
}

*:focus-visible { outline: none; box-shadow: var(--focus-ring); }
@media (prefers-reduced-motion: reduce) { * { transition-duration: .001ms !important; animation-duration: .001ms !important; } }
```

- [ ] **Step 4: Wire imports in `main.tsx`**

In `frontend/src/main.tsx`, add `theme.css` immediately after `tokens.css` so it overrides the old values (later import wins for equal specificity):

```tsx
import './styles.css';
import './styles/tokens.css';
import './styles/theme.css';   // ← new: redefines --ck-* onto the new palette
import './styles/skeleton.css';
import './styles/sidebar.css';
import './styles/drawers.css';
import './styles/attendance.css';
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/styles/theme.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/styles/theme.css frontend/src/styles/theme.test.ts frontend/src/main.tsx
git commit -m "feat(design): add Slate & Signal token layer (light+dark)"
```

---

### Task 2: Theme hook (`useTheme`)

**Files:**
- Create: `frontend/src/hooks/useTheme.ts`, `frontend/src/hooks/useTheme.test.ts`

**Interfaces:**
- Produces: `type ThemeChoice = 'light'|'dark'|'system'`; `useTheme(): { theme: ThemeChoice; setTheme(t: ThemeChoice): void; resolved: 'light'|'dark' }`. Persists to `localStorage['ck_theme']`, stamps `data-theme` on `document.documentElement` (removes the attribute for `'system'`).

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/hooks/useTheme.test.ts
import { renderHook, act } from '@testing-library/react';
import { afterEach, describe, it, expect } from 'vitest';
import { useTheme } from './useTheme';

afterEach(() => { localStorage.clear(); document.documentElement.removeAttribute('data-theme'); });

describe('useTheme', () => {
  it('defaults to system with no data-theme attribute', () => {
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe('system');
    expect(document.documentElement.getAttribute('data-theme')).toBeNull();
  });
  it('stamps data-theme and persists when set to dark', () => {
    const { result } = renderHook(() => useTheme());
    act(() => result.current.setTheme('dark'));
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('ck_theme')).toBe('dark');
  });
  it('removes data-theme when set back to system', () => {
    const { result } = renderHook(() => useTheme());
    act(() => result.current.setTheme('dark'));
    act(() => result.current.setTheme('system'));
    expect(document.documentElement.getAttribute('data-theme')).toBeNull();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/hooks/useTheme.test.ts`
Expected: FAIL — cannot find `./useTheme`.

- [ ] **Step 3: Implement**

```ts
// frontend/src/hooks/useTheme.ts
import { useCallback, useEffect, useState } from 'react';

export type ThemeChoice = 'light' | 'dark' | 'system';
const KEY = 'ck_theme';

function apply(choice: ThemeChoice) {
  const el = document.documentElement;
  if (choice === 'system') el.removeAttribute('data-theme');
  else el.setAttribute('data-theme', choice);
}

function prefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches;
}

export function useTheme() {
  const [theme, setThemeState] = useState<ThemeChoice>(() => {
    try { return (localStorage.getItem(KEY) as ThemeChoice) || 'system'; } catch { return 'system'; }
  });

  useEffect(() => { apply(theme); }, [theme]);

  const setTheme = useCallback((t: ThemeChoice) => {
    try { localStorage.setItem(KEY, t); } catch { /* ignore */ }
    setThemeState(t);
  }, []);

  const resolved: 'light' | 'dark' = theme === 'system' ? (prefersDark() ? 'dark' : 'light') : theme;
  return { theme, setTheme, resolved };
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npx vitest run src/hooks/useTheme.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/hooks/useTheme.ts frontend/src/hooks/useTheme.test.ts
git commit -m "feat(design): useTheme hook (system/light/dark, persisted)"
```

---

### Task 3: Self-host IBM Plex fonts

**Files:**
- Modify: `frontend/package.json`, `frontend/src/main.tsx`

**Interfaces:**
- Produces: `'IBM Plex Sans'` / `'IBM Plex Mono'` available to `--font-ui` / `--font-mono`, self-hosted (no CDN). Sets `body { font-family: var(--font-ui); }`.

- [ ] **Step 1: Install self-hosted font packages**

Run:
```bash
cd frontend && npm install @fontsource/ibm-plex-sans@^5 @fontsource/ibm-plex-mono@^5
```
Expected: both added to `dependencies`; woff2 files vendored under `node_modules/@fontsource/*` (Vite bundles them — no network at runtime).

- [ ] **Step 2: Import the weights in `main.tsx`**

Add at the very top of `frontend/src/main.tsx` (before the CSS imports):

```tsx
import '@fontsource/ibm-plex-sans/400.css';
import '@fontsource/ibm-plex-sans/500.css';
import '@fontsource/ibm-plex-sans/600.css';
import '@fontsource/ibm-plex-sans/700.css';
import '@fontsource/ibm-plex-mono/400.css';
```

- [ ] **Step 3: Apply the UI font + tabular numerals globally**

Append to `frontend/src/styles/theme.css`:

```css
body { font-family: var(--font-ui); }
.num, [data-numeric] { font-variant-numeric: tabular-nums lining-nums; }
```

- [ ] **Step 4: Verify build resolves the fonts**

Run: `cd frontend && npm run build`
Expected: build succeeds; `dist/assets` contains `ibm-plex-sans-*.woff2` files.

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/main.tsx frontend/src/styles/theme.css
git commit -m "feat(design): self-host IBM Plex Sans/Mono, tabular numerals"
```

---

## Phase B — Shared component kit

### Task 4: `formatDelta` util

**Files:**
- Modify: `frontend/src/pages/workspace/utils.ts`
- Create: `frontend/src/pages/workspace/utils.formatDelta.test.ts`

**Interfaces:**
- Produces: `interface Delta { direction: 'up'|'down'|'flat'; arrow: '▲'|'▼'|'→'; pctText: string; }` and `formatDelta(current: number, previous: number): Delta`.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/pages/workspace/utils.formatDelta.test.ts
import { describe, it, expect } from 'vitest';
import { formatDelta } from './utils';

describe('formatDelta', () => {
  it('reports an increase', () => {
    expect(formatDelta(112, 100)).toEqual({ direction: 'up', arrow: '▲', pctText: '12%' });
  });
  it('reports a decrease as an absolute magnitude', () => {
    expect(formatDelta(90, 100)).toEqual({ direction: 'down', arrow: '▼', pctText: '10%' });
  });
  it('treats equal values as flat', () => {
    expect(formatDelta(50, 50)).toEqual({ direction: 'flat', arrow: '→', pctText: '0%' });
  });
  it('handles a zero baseline without dividing by zero', () => {
    expect(formatDelta(5, 0)).toEqual({ direction: 'up', arrow: '▲', pctText: '100%' });
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/pages/workspace/utils.formatDelta.test.ts`
Expected: FAIL — `formatDelta` is not exported.

- [ ] **Step 3: Implement (append to `utils.ts`)**

```ts
export interface Delta { direction: 'up' | 'down' | 'flat'; arrow: '▲' | '▼' | '→'; pctText: string; }

export function formatDelta(current: number, previous: number): Delta {
  const diff = current - previous;
  const direction = diff > 0 ? 'up' : diff < 0 ? 'down' : 'flat';
  const arrow = direction === 'up' ? '▲' : direction === 'down' ? '▼' : '→';
  const pct = previous === 0 ? (current === 0 ? 0 : 100) : (Math.abs(diff) / Math.abs(previous)) * 100;
  return { direction, arrow, pctText: `${Math.round(pct)}%` };
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npx vitest run src/pages/workspace/utils.formatDelta.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/utils.ts frontend/src/pages/workspace/utils.formatDelta.test.ts
git commit -m "feat(design): formatDelta helper for KPI deltas"
```

---

### Task 5: `StatusPill` component + kit CSS

**Files:**
- Create: `frontend/src/styles/ui-kit.css`, `frontend/src/components/ui/StatusPill.tsx`, `frontend/src/components/ui/StatusPill.test.tsx`
- Modify: `frontend/src/main.tsx` (import `ui-kit.css`)

**Interfaces:**
- Consumes: tokens from Task 1.
- Produces: `type PillTone = 'paid'|'partial'|'overdue'|'pending'|'info'|'neutral'`; `prettyStatus(raw: string): string`; `<StatusPill status={string} />` (maps common API statuses → tone + prettified label).

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/StatusPill.test.tsx
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect } from 'vitest';
import { StatusPill, prettyStatus } from './StatusPill';

afterEach(cleanup);

describe('prettyStatus', () => {
  it('sentence-cases ALL_CAPS API values', () => {
    expect(prettyStatus('AWAITING_APPROVAL')).toBe('Awaiting approval');
    expect(prettyStatus('PAID')).toBe('Paid');
  });
});

describe('StatusPill', () => {
  it('renders the label and maps overdue to the danger tone', () => {
    render(<StatusPill status="OVERDUE" />);
    const pill = screen.getByText('Overdue');
    expect(pill.className).toContain('ck-pill2--overdue');
  });
  it('falls back to neutral for unknown statuses', () => {
    render(<StatusPill status="WEIRD_STATE" />);
    expect(screen.getByText('Weird state').className).toContain('ck-pill2--neutral');
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/ui/StatusPill.test.tsx`
Expected: FAIL — cannot find `./StatusPill`.

- [ ] **Step 3: Create `ui-kit.css` (start it here; later tasks append)**

```css
/* frontend/src/styles/ui-kit.css — token-driven classes for the design-language kit. */

/* Status pill */
.ck-pill2 { display:inline-flex; align-items:center; gap:6px; font-size:12px; font-weight:700;
  padding:3px 9px; border-radius:var(--r-pill); line-height:1.4; }
.ck-pill2::before { content:""; width:6px; height:6px; border-radius:50%; background:currentColor; flex:none; }
.ck-pill2--paid { color:var(--ok); background:var(--ok-soft); }
.ck-pill2--partial { color:var(--warn); background:var(--warn-soft); }
.ck-pill2--pending { color:var(--warn); background:var(--warn-soft); }
.ck-pill2--overdue { color:var(--danger); background:var(--danger-soft); }
.ck-pill2--info { color:var(--info); background:var(--info-soft); }
.ck-pill2--neutral { color:var(--text-secondary); background:var(--bg-raised); }
```

- [ ] **Step 4: Implement `StatusPill.tsx`**

```tsx
// frontend/src/components/ui/StatusPill.tsx
export type PillTone = 'paid' | 'partial' | 'overdue' | 'pending' | 'info' | 'neutral';

const TONE_BY_STATUS: Record<string, PillTone> = {
  PAID: 'paid', COMPLETED: 'paid', APPROVED: 'paid', ACTIVE: 'paid', SUBMITTED: 'paid',
  PARTIAL: 'partial',
  PENDING: 'pending', AWAITING_APPROVAL: 'pending', PROCESSING: 'pending', DRAFT: 'pending',
  OVERDUE: 'overdue', REJECTED: 'overdue', FAILED: 'overdue',
  INFO: 'info', IN_PROGRESS: 'info',
};

export function prettyStatus(raw: string): string {
  const v = String(raw || '').trim();
  if (!v) return '—';
  const lower = v.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

export function StatusPill({ status }: { status: string }) {
  const tone = TONE_BY_STATUS[String(status || '').toUpperCase()] ?? 'neutral';
  return <span className={`ck-pill2 ck-pill2--${tone}`}>{prettyStatus(status)}</span>;
}
```

- [ ] **Step 5: Import `ui-kit.css` in `main.tsx`** (after `theme.css`)

```tsx
import './styles/theme.css';
import './styles/ui-kit.css';   // ← new
```

- [ ] **Step 6: Run to verify it passes**

Run: `cd frontend && npx vitest run src/components/ui/StatusPill.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/styles/ui-kit.css frontend/src/components/ui/StatusPill.tsx frontend/src/components/ui/StatusPill.test.tsx frontend/src/main.tsx
git commit -m "feat(design): StatusPill + ui-kit.css"
```

---

### Task 6: `Button` component

**Files:**
- Create: `frontend/src/components/ui/Button.tsx`, `frontend/src/components/ui/Button.test.tsx`
- Modify: `frontend/src/styles/ui-kit.css` (append)

**Interfaces:**
- Produces: `<Button variant? size? {...buttonProps}>` where `variant: 'primary'|'secondary'|'ghost'|'danger'` (default `'primary'`), `size: 'sm'|'md'|'lg'` (default `'md'` → heights 32/36/40 by token). Forwards all native `<button>` props.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/Button.test.tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { Button } from './Button';

afterEach(cleanup);

describe('Button', () => {
  it('defaults to primary/md and fires onClick', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Save changes</Button>);
    const btn = screen.getByRole('button', { name: 'Save changes' });
    expect(btn.className).toContain('ck-btn2--primary');
    expect(btn.className).toContain('ck-btn2--md');
    fireEvent.click(btn);
    expect(onClick).toHaveBeenCalledOnce();
  });
  it('applies the requested variant and respects disabled', () => {
    const onClick = vi.fn();
    render(<Button variant="danger" disabled onClick={onClick}>Delete</Button>);
    const btn = screen.getByRole('button', { name: 'Delete' });
    expect(btn.className).toContain('ck-btn2--danger');
    fireEvent.click(btn);
    expect(onClick).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/ui/Button.test.tsx`
Expected: FAIL — cannot find `./Button`.

- [ ] **Step 3: Append button CSS to `ui-kit.css`**

```css
/* Button */
.ck-btn2 { display:inline-flex; align-items:center; justify-content:center; gap:7px; font-family:inherit;
  font-weight:600; border-radius:var(--r-sm); border:1px solid transparent; cursor:pointer;
  transition:background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease); white-space:nowrap; }
.ck-btn2:disabled { opacity:.45; cursor:not-allowed; }
.ck-btn2--sm { height:32px; padding:0 12px; font-size:13px; }
.ck-btn2--md { height:36px; padding:0 14px; font-size:14px; }
.ck-btn2--lg { height:40px; padding:0 18px; font-size:14px; }
.ck-btn2--primary { background:var(--accent); color:var(--accent-on); }
.ck-btn2--primary:hover:not(:disabled) { background:var(--accent-ink); }
.ck-btn2--secondary { background:var(--bg-surface); color:var(--text-primary); border-color:var(--border-default); }
.ck-btn2--secondary:hover:not(:disabled) { background:var(--bg-raised); }
.ck-btn2--ghost { background:transparent; color:var(--text-secondary); }
.ck-btn2--ghost:hover:not(:disabled) { background:var(--bg-raised); color:var(--accent-ink); }
.ck-btn2--danger { background:var(--danger); color:#fff; }
```

- [ ] **Step 4: Implement `Button.tsx`**

```tsx
// frontend/src/components/ui/Button.tsx
import type { ButtonHTMLAttributes } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
}

export function Button({ variant = 'primary', size = 'md', className = '', type = 'button', ...rest }: ButtonProps) {
  return <button type={type} className={`ck-btn2 ck-btn2--${variant} ck-btn2--${size} ${className}`.trim()} {...rest} />;
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd frontend && npx vitest run src/components/ui/Button.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ui/Button.tsx frontend/src/components/ui/Button.test.tsx frontend/src/styles/ui-kit.css
git commit -m "feat(design): Button (variants + sizes)"
```

---

### Task 7: `StatCard` component

**Files:**
- Create: `frontend/src/components/ui/StatCard.tsx`, `frontend/src/components/ui/StatCard.test.tsx`
- Modify: `frontend/src/styles/ui-kit.css` (append)

**Interfaces:**
- Consumes: `Delta` from Task 4 (`formatDelta`).
- Produces: `<StatCard label value sub? delta? sparkline? onClick? />` where `value: string|number`, `delta?: Delta`, `sparkline?: number[]` (0–100 scaled polyline points), renders label + value + delta chip (arrow, ok/danger tone) + sub + inline SVG sparkline.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/StatCard.test.tsx
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect } from 'vitest';
import { StatCard } from './StatCard';
import { formatDelta } from '../../pages/workspace/utils';

afterEach(cleanup);

describe('StatCard', () => {
  it('renders label, value and sub', () => {
    render(<StatCard label="Fees collected" value="₹42.8L" sub="68% of target" />);
    expect(screen.getByText('Fees collected')).toBeTruthy();
    expect(screen.getByText('₹42.8L')).toBeTruthy();
    expect(screen.getByText('68% of target')).toBeTruthy();
  });
  it('shows an up delta with the ▲ arrow and ok tone', () => {
    render(<StatCard label="Attendance" value="94%" delta={formatDelta(94, 92)} />);
    const chip = screen.getByText(/▲/);
    expect(chip.className).toContain('ck-delta--up');
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/ui/StatCard.test.tsx`
Expected: FAIL — cannot find `./StatCard`.

- [ ] **Step 3: Append StatCard CSS to `ui-kit.css`**

```css
/* Stat card */
.ck-stat2 { display:block; width:100%; text-align:left; background:var(--bg-surface); border:1px solid var(--border-subtle);
  border-radius:var(--r-md); padding:13px 14px 10px; cursor:default; }
button.ck-stat2 { cursor:pointer; font-family:inherit; }
.ck-stat2-l { font-size:11px; font-weight:600; color:var(--text-muted); text-transform:uppercase; letter-spacing:.05em; }
.ck-stat2-v { display:flex; align-items:baseline; gap:8px; font-size:23px; font-weight:700; letter-spacing:-.02em;
  margin-top:6px; font-variant-numeric:tabular-nums lining-nums; color:var(--text-primary); }
.ck-delta { font-size:11.5px; font-weight:700; padding:2px 6px; border-radius:var(--r-sm); }
.ck-delta--up { color:var(--ok); background:var(--ok-soft); }
.ck-delta--down { color:var(--danger); background:var(--danger-soft); }
.ck-delta--flat { color:var(--text-secondary); background:var(--bg-raised); }
.ck-stat2-s { font-size:11.5px; color:var(--text-secondary); margin-top:3px; }
.ck-stat2 svg { margin-top:8px; display:block; width:100%; height:26px; overflow:visible; }
```

- [ ] **Step 4: Implement `StatCard.tsx`**

```tsx
// frontend/src/components/ui/StatCard.tsx
import type { Delta } from '../../pages/workspace/utils';

interface StatCardProps {
  label: string;
  value: string | number;
  sub?: string;
  delta?: Delta;
  sparkline?: number[]; // values in 0..100; drawn left→right
  onClick?: () => void;
}

function sparkPoints(values: number[]): string {
  if (values.length < 2) return '';
  const max = Math.max(...values), min = Math.min(...values), span = max - min || 1;
  const stepX = 120 / (values.length - 1);
  return values.map((v, i) => `${(i * stepX).toFixed(1)},${(24 - ((v - min) / span) * 22).toFixed(1)}`).join(' ');
}

export function StatCard({ label, value, sub, delta, sparkline, onClick }: StatCardProps) {
  const Tag = onClick ? 'button' : 'div';
  const pts = sparkline ? sparkPoints(sparkline) : '';
  return (
    <Tag className="ck-stat2" {...(onClick ? { onClick, type: 'button' as const } : {})}>
      <div className="ck-stat2-l">{label}</div>
      <div className="ck-stat2-v">
        <span>{value}</span>
        {delta && <span className={`ck-delta ck-delta--${delta.direction}`}>{delta.arrow} {delta.pctText}</span>}
      </div>
      {sub && <div className="ck-stat2-s">{sub}</div>}
      {pts && (
        <svg viewBox="0 0 120 26" preserveAspectRatio="none" aria-hidden="true">
          <polyline fill="none" stroke="var(--accent)" strokeWidth="2" points={pts} />
        </svg>
      )}
    </Tag>
  );
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd frontend && npx vitest run src/components/ui/StatCard.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ui/StatCard.tsx frontend/src/components/ui/StatCard.test.tsx frontend/src/styles/ui-kit.css
git commit -m "feat(design): StatCard with delta + sparkline"
```

---

### Task 8: `useDensity` hook

**Files:**
- Create: `frontend/src/hooks/useDensity.ts`, `frontend/src/hooks/useDensity.test.ts`
- Modify: `frontend/src/styles/ui-kit.css` (append density row-height vars)

**Interfaces:**
- Produces: `type Density = 'compact'|'default'|'comfortable'`; `useDensity(): { density: Density; setDensity(d: Density): void }`. Persists `localStorage['ck_density']`, stamps `data-density` on `document.documentElement`. CSS exposes `--row-h` per `data-density`.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/hooks/useDensity.test.ts
import { renderHook, act } from '@testing-library/react';
import { afterEach, describe, it, expect } from 'vitest';
import { useDensity } from './useDensity';

afterEach(() => { localStorage.clear(); document.documentElement.removeAttribute('data-density'); });

describe('useDensity', () => {
  it('defaults to "default" and stamps the attribute', () => {
    const { result } = renderHook(() => useDensity());
    expect(result.current.density).toBe('default');
    expect(document.documentElement.getAttribute('data-density')).toBe('default');
  });
  it('persists and applies a new density', () => {
    const { result } = renderHook(() => useDensity());
    act(() => result.current.setDensity('compact'));
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
    expect(localStorage.getItem('ck_density')).toBe('compact');
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/hooks/useDensity.test.ts`
Expected: FAIL — cannot find `./useDensity`.

- [ ] **Step 3: Implement the hook**

```ts
// frontend/src/hooks/useDensity.ts
import { useCallback, useEffect, useState } from 'react';

export type Density = 'compact' | 'default' | 'comfortable';
const KEY = 'ck_density';

export function useDensity() {
  const [density, setDensityState] = useState<Density>(() => {
    try { return (localStorage.getItem(KEY) as Density) || 'default'; } catch { return 'default'; }
  });
  useEffect(() => { document.documentElement.setAttribute('data-density', density); }, [density]);
  const setDensity = useCallback((d: Density) => {
    try { localStorage.setItem(KEY, d); } catch { /* ignore */ }
    setDensityState(d);
  }, []);
  return { density, setDensity };
}
```

- [ ] **Step 4: Append density CSS to `ui-kit.css`**

```css
/* Density — consumed by tables/rows via var(--row-h) */
:root { --row-h:40px; }
:root[data-density="compact"] { --row-h:32px; }
:root[data-density="comfortable"] { --row-h:48px; }
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd frontend && npx vitest run src/hooks/useDensity.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/hooks/useDensity.ts frontend/src/hooks/useDensity.test.ts frontend/src/styles/ui-kit.css
git commit -m "feat(design): useDensity hook + row-height tokens"
```

---

### Task 9: `SidePanel` (non-modal edit panel)

**Files:**
- Create: `frontend/src/components/ui/SidePanel.tsx`, `frontend/src/components/ui/SidePanel.test.tsx`
- Modify: `frontend/src/styles/ui-kit.css` (append)

**Interfaces:**
- Produces: `<SidePanel open title onClose {children} />` — right-anchored slide-in, `role="dialog" aria-modal="false"` (background stays interactive), closes on Esc and on the close button, focuses the panel heading on open. Renders nothing when `open` is false.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/SidePanel.test.tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { SidePanel } from './SidePanel';

afterEach(cleanup);

describe('SidePanel', () => {
  it('renders nothing when closed', () => {
    render(<SidePanel open={false} title="Edit student" onClose={vi.fn()}>body</SidePanel>);
    expect(screen.queryByText('Edit student')).toBeNull();
  });
  it('renders title/children and closes via button and Escape', () => {
    const onClose = vi.fn();
    render(<SidePanel open title="Edit student" onClose={onClose}><p>Form here</p></SidePanel>);
    expect(screen.getByText('Form here')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /close/i }));
    expect(onClose).toHaveBeenCalledOnce();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/ui/SidePanel.test.tsx`
Expected: FAIL — cannot find `./SidePanel`.

- [ ] **Step 3: Append SidePanel CSS to `ui-kit.css`**

```css
/* Side panel (non-modal) */
.ck-sidepanel { position:fixed; top:0; right:0; bottom:0; width:min(440px,92vw); background:var(--bg-surface);
  border-left:1px solid var(--border-default); box-shadow:var(--shadow-drawer); z-index:50;
  display:flex; flex-direction:column; animation:ck-slidein var(--dur-base) var(--ease); }
@keyframes ck-slidein { from { transform:translateX(16px); opacity:.6; } to { transform:none; opacity:1; } }
.ck-sidepanel-h { display:flex; align-items:center; justify-content:space-between; padding:16px 18px;
  border-bottom:1px solid var(--border-subtle); }
.ck-sidepanel-h h2 { font-size:16px; font-weight:700; color:var(--text-primary); }
.ck-sidepanel-x { width:30px; height:30px; border-radius:var(--r-sm); border:1px solid var(--border-default);
  background:var(--bg-surface); color:var(--text-secondary); cursor:pointer; font-size:16px; }
.ck-sidepanel-body { padding:18px; overflow-y:auto; }
```

- [ ] **Step 4: Implement `SidePanel.tsx`**

```tsx
// frontend/src/components/ui/SidePanel.tsx
import { useEffect, useRef, type ReactNode } from 'react';

interface SidePanelProps { open: boolean; title: string; onClose: () => void; children: ReactNode; }

export function SidePanel({ open, title, onClose, children }: SidePanelProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (!open) return;
    headingRef.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <aside className="ck-sidepanel" role="dialog" aria-modal="false" aria-label={title}>
      <div className="ck-sidepanel-h">
        <h2 ref={headingRef} tabIndex={-1}>{title}</h2>
        <button className="ck-sidepanel-x" onClick={onClose} aria-label="Close panel">✕</button>
      </div>
      <div className="ck-sidepanel-body">{children}</div>
    </aside>
  );
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd frontend && npx vitest run src/components/ui/SidePanel.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ui/SidePanel.tsx frontend/src/components/ui/SidePanel.test.tsx frontend/src/styles/ui-kit.css
git commit -m "feat(design): non-modal SidePanel"
```

---

### Task 10: `CommandPalette` + `fuzzy` filter

**Files:**
- Create: `frontend/src/components/ui/fuzzy.ts`, `frontend/src/components/ui/fuzzy.test.ts`, `frontend/src/components/ui/CommandPalette.tsx`, `frontend/src/components/ui/CommandPalette.test.tsx`
- Modify: `frontend/src/styles/ui-kit.css` (append)

**Interfaces:**
- Produces:
  - `interface CommandItem { id: string; label: string; group?: string; onSelect: () => void; }`
  - `fuzzyFilter(items: CommandItem[], query: string): CommandItem[]` — case-insensitive subsequence match; empty query returns all.
  - `<CommandPalette open items onClose />` — text input filters items; ArrowUp/Down move highlight; Enter runs the highlighted item's `onSelect` then `onClose`; Esc closes. Renders nothing when closed. (The global ⌘K key handler that flips `open` is wired in the shell task, not here.)

- [ ] **Step 1: Write the failing tests**

```ts
// frontend/src/components/ui/fuzzy.test.ts
import { describe, it, expect } from 'vitest';
import { fuzzyFilter, type CommandItem } from './fuzzy';

const items: CommandItem[] = [
  { id: 'students', label: 'Students', onSelect() {} },
  { id: 'fees', label: 'Fee Collections', onSelect() {} },
  { id: 'attendance', label: 'Attendance', onSelect() {} },
];

describe('fuzzyFilter', () => {
  it('returns all items for an empty query', () => {
    expect(fuzzyFilter(items, '')).toHaveLength(3);
  });
  it('matches by case-insensitive subsequence', () => {
    expect(fuzzyFilter(items, 'fee').map(i => i.id)).toEqual(['fees']);
    expect(fuzzyFilter(items, 'atn').map(i => i.id)).toEqual(['attendance']);
  });
  it('returns nothing when no item matches', () => {
    expect(fuzzyFilter(items, 'zzz')).toEqual([]);
  });
});
```

```tsx
// frontend/src/components/ui/CommandPalette.test.tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { CommandPalette } from './CommandPalette';

afterEach(cleanup);

const items = [
  { id: 'students', label: 'Students', onSelect: vi.fn() },
  { id: 'fees', label: 'Fee Collections', onSelect: vi.fn() },
];

describe('CommandPalette', () => {
  it('renders nothing when closed', () => {
    render(<CommandPalette open={false} items={items} onClose={vi.fn()} />);
    expect(screen.queryByPlaceholderText(/search/i)).toBeNull();
  });
  it('filters and runs the selected item on Enter', () => {
    const onClose = vi.fn();
    render(<CommandPalette open items={items} onClose={onClose} />);
    const input = screen.getByPlaceholderText(/search/i);
    fireEvent.change(input, { target: { value: 'fee' } });
    expect(screen.getByText('Fee Collections')).toBeTruthy();
    expect(screen.queryByText('Students')).toBeNull();
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(items[1].onSelect).toHaveBeenCalledOnce();
    expect(onClose).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/components/ui/fuzzy.test.ts src/components/ui/CommandPalette.test.tsx`
Expected: FAIL — modules not found.

- [ ] **Step 3: Implement `fuzzy.ts`**

```ts
// frontend/src/components/ui/fuzzy.ts
export interface CommandItem { id: string; label: string; group?: string; onSelect: () => void; }

function isSubsequence(needle: string, haystack: string): boolean {
  let i = 0;
  for (let j = 0; j < haystack.length && i < needle.length; j++) {
    if (haystack[j] === needle[i]) i++;
  }
  return i === needle.length;
}

export function fuzzyFilter(items: CommandItem[], query: string): CommandItem[] {
  const q = query.trim().toLowerCase();
  if (!q) return items;
  return items.filter(it => isSubsequence(q, it.label.toLowerCase()));
}
```

- [ ] **Step 4: Append CommandPalette CSS to `ui-kit.css`**

```css
/* Command palette */
.ck-cmdk-scrim { position:fixed; inset:0; background:var(--bg-overlay); z-index:60; display:flex; justify-content:center; align-items:flex-start; padding-top:12vh; }
.ck-cmdk { width:min(560px,92vw); background:var(--bg-surface); border:1px solid var(--border-default);
  border-radius:var(--r-lg); box-shadow:var(--shadow-popover); overflow:hidden; }
.ck-cmdk input { width:100%; border:none; outline:none; padding:16px 18px; font-size:15px; font-family:inherit;
  background:var(--bg-surface); color:var(--text-primary); border-bottom:1px solid var(--border-subtle); }
.ck-cmdk-list { max-height:340px; overflow-y:auto; padding:6px; }
.ck-cmdk-item { display:flex; align-items:center; gap:10px; padding:9px 12px; border-radius:var(--r-sm);
  font-size:14px; color:var(--text-primary); cursor:pointer; }
.ck-cmdk-item[aria-selected="true"] { background:var(--accent-soft); color:var(--accent-ink); }
.ck-cmdk-empty { padding:16px 18px; color:var(--text-muted); font-size:13px; }
```

- [ ] **Step 5: Implement `CommandPalette.tsx`**

```tsx
// frontend/src/components/ui/CommandPalette.tsx
import { useEffect, useMemo, useState } from 'react';
import { fuzzyFilter, type CommandItem } from './fuzzy';

interface Props { open: boolean; items: CommandItem[]; onClose: () => void; }

export function CommandPalette({ open, items, onClose }: Props) {
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const results = useMemo(() => fuzzyFilter(items, query), [items, query]);

  useEffect(() => { if (open) { setQuery(''); setActive(0); } }, [open]);
  useEffect(() => { setActive(0); }, [query]);

  if (!open) return null;

  const run = (item?: CommandItem) => { if (item) { item.onSelect(); onClose(); } };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive(a => Math.min(a + 1, results.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(a - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); run(results[active]); }
    else if (e.key === 'Escape') { e.preventDefault(); onClose(); }
  };

  return (
    <div className="ck-cmdk-scrim" onClick={onClose}>
      <div className="ck-cmdk" role="dialog" aria-label="Command palette" onClick={e => e.stopPropagation()}>
        <input autoFocus placeholder="Search or jump to…" value={query}
          onChange={e => setQuery(e.target.value)} onKeyDown={onKey} aria-label="Search or jump to" />
        <div className="ck-cmdk-list" role="listbox">
          {results.length === 0 && <div className="ck-cmdk-empty">No matches</div>}
          {results.map((item, i) => (
            <div key={item.id} role="option" aria-selected={i === active}
              className="ck-cmdk-item" onMouseEnter={() => setActive(i)} onClick={() => run(item)}>
              {item.label}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 6: Run to verify they pass**

Run: `cd frontend && npx vitest run src/components/ui/fuzzy.test.ts src/components/ui/CommandPalette.test.tsx`
Expected: PASS (fuzzy 3, palette 2).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/ui/fuzzy.ts frontend/src/components/ui/fuzzy.test.ts frontend/src/components/ui/CommandPalette.tsx frontend/src/components/ui/CommandPalette.test.tsx frontend/src/styles/ui-kit.css
git commit -m "feat(design): CommandPalette + fuzzy filter"
```

---

## Phase C — App shell adoption

### Task 11: `NavRail` component

**Files:**
- Create: `frontend/src/components/ui/NavRail.tsx`, `frontend/src/components/ui/NavRail.test.tsx`
- Modify: `frontend/src/styles/ui-kit.css` (append)

**Interfaces:**
- Consumes: the nav-section shape from `pages/workspace/config.ts` (`{ title; fire?; items: { key: PanelKey; label; icon; module? }[] }`) and `PanelKey`.
- Produces: `<NavRail sections activePanel onSelect openGroups onToggleGroup schoolName userLabel badges? />` where `badges?: Partial<Record<PanelKey, { count: number; urgent?: boolean }>>`. Renders the navy rail: brand, school line, collapsible groups (faint uppercase headers), active item highlight, count badges. Pure presentational — no data fetching, no panel state ownership.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/NavRail.test.tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { NavRail } from './NavRail';

afterEach(cleanup);

const sections = [
  { title: 'Overview', items: [{ key: 'home', label: 'Dashboard', icon: '▦' }] },
  { title: 'Finance', items: [{ key: 'fees', label: 'Fee Collections', icon: '₹', module: 'FEES' }] },
] as any;

describe('NavRail', () => {
  it('renders groups, items and a badge, and marks the active item', () => {
    render(<NavRail sections={sections} activePanel="fees" onSelect={vi.fn()}
      openGroups={{}} onToggleGroup={vi.fn()} schoolName="DPS Sector 45" userLabel="Ananya Rao"
      badges={{ fees: { count: 3 } }} />);
    expect(screen.getByText('Fee Collections')).toBeTruthy();
    expect(screen.getByText('3')).toBeTruthy();
    const active = document.querySelector('.ck-rail-item--active');
    expect(active?.textContent).toContain('Fee Collections');
  });
  it('calls onSelect with the panel key', () => {
    const onSelect = vi.fn();
    render(<NavRail sections={sections} activePanel="home" onSelect={onSelect}
      openGroups={{}} onToggleGroup={vi.fn()} schoolName="DPS" userLabel="AR" />);
    fireEvent.click(screen.getByText('Fee Collections'));
    expect(onSelect).toHaveBeenCalledWith('fees');
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/ui/NavRail.test.tsx`
Expected: FAIL — cannot find `./NavRail`.

- [ ] **Step 3: Append NavRail CSS to `ui-kit.css`**

```css
/* Nav rail */
.ck-rail { background:var(--rail-bg); color:var(--rail-text); width:216px; display:flex; flex-direction:column;
  gap:2px; padding:14px 12px; border-right:1px solid var(--rail-border); height:100%; overflow-y:auto; }
.ck-rail-brand { display:flex; align-items:center; gap:9px; padding:4px 6px 2px; font-weight:700; font-size:15px; }
.ck-rail-brand .mk { width:22px; height:22px; border-radius:7px; background:var(--accent); color:var(--accent-on);
  display:grid; place-items:center; font-size:13px; }
.ck-rail-school { font-size:11.5px; color:var(--rail-text-muted); padding:2px 6px 12px;
  border-bottom:1px solid var(--rail-border); margin-bottom:6px; }
.ck-rail-sec { font-size:10px; font-weight:600; letter-spacing:.12em; text-transform:uppercase;
  color:var(--rail-text-faint); padding:12px 8px 5px; background:none; border:none; width:100%; text-align:left; cursor:pointer; }
.ck-rail-item { display:flex; align-items:center; gap:10px; padding:7px 9px; border-radius:var(--r-md);
  color:var(--rail-text); font-size:12.5px; font-weight:500; background:none; border:none; width:100%; text-align:left; cursor:pointer; }
.ck-rail-item .ic { width:16px; text-align:center; opacity:.72; }
.ck-rail-item--active { background:var(--rail-active-bg); color:var(--rail-active-text); font-weight:600; }
.ck-rail-item--active .ic { opacity:1; }
.ck-rail-badge { margin-left:auto; font-size:10.5px; font-weight:700; font-variant-numeric:tabular-nums;
  background:var(--rail-badge-bg); color:var(--rail-badge-text); border-radius:var(--r-pill); padding:1px 7px; }
.ck-rail-badge--urgent { background:var(--rail-badge-warn-bg); color:var(--rail-badge-warn-text); }
.ck-rail-spacer { flex:1; }
.ck-rail-user { display:flex; align-items:center; gap:9px; padding:9px 8px 2px;
  border-top:1px solid var(--rail-border); margin-top:8px; font-size:12px; color:var(--rail-text-muted); }
```

- [ ] **Step 4: Implement `NavRail.tsx`**

```tsx
// frontend/src/components/ui/NavRail.tsx
import type { PanelKey } from '../../pages/workspace/config';

interface NavItem { key: PanelKey; label: string; icon: string; module?: string; }
interface NavSection { title: string; fire?: boolean; items: NavItem[]; }
interface Badge { count: number; urgent?: boolean; }

interface NavRailProps {
  sections: NavSection[];
  activePanel: PanelKey;
  onSelect: (key: PanelKey) => void;
  openGroups: Record<string, boolean>;
  onToggleGroup: (title: string) => void;
  schoolName: string;
  userLabel: string;
  badges?: Partial<Record<PanelKey, Badge>>;
}

export function NavRail({ sections, activePanel, onSelect, openGroups, onToggleGroup, schoolName, userLabel, badges }: NavRailProps) {
  return (
    <nav className="ck-rail" aria-label="Primary">
      <div className="ck-rail-brand"><span className="mk">◆</span>Custoking</div>
      <div className="ck-rail-school">{schoolName}</div>
      {sections.map(sec => {
        const open = openGroups[sec.title] ?? true;
        return (
          <div key={sec.title}>
            <button className="ck-rail-sec" onClick={() => onToggleGroup(sec.title)} aria-expanded={open}>{sec.title}</button>
            {open && sec.items.map(item => {
              const badge = badges?.[item.key];
              return (
                <button key={item.key} className={`ck-rail-item${item.key === activePanel ? ' ck-rail-item--active' : ''}`}
                  onClick={() => onSelect(item.key)} aria-current={item.key === activePanel ? 'page' : undefined}>
                  <span className="ic" aria-hidden="true">{item.icon}</span>{item.label}
                  {badge && <span className={`ck-rail-badge${badge.urgent ? ' ck-rail-badge--urgent' : ''}`}>{badge.count}</span>}
                </button>
              );
            })}
          </div>
        );
      })}
      <div className="ck-rail-spacer" />
      <div className="ck-rail-user">{userLabel}</div>
    </nav>
  );
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd frontend && npx vitest run src/components/ui/NavRail.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ui/NavRail.tsx frontend/src/components/ui/NavRail.test.tsx frontend/src/styles/ui-kit.css
git commit -m "feat(design): NavRail (navy shell, presentational)"
```

---

### Task 12: `Topbar` component

**Files:**
- Create: `frontend/src/components/ui/Topbar.tsx`, `frontend/src/components/ui/Topbar.test.tsx`
- Modify: `frontend/src/styles/ui-kit.css` (append)

**Interfaces:**
- Consumes: `Button` (Task 6), `ThemeChoice`/`useTheme` result shape (Task 2).
- Produces: `<Topbar title context? onOpenPalette onNew? onCycleTheme themeLabel userLabel />` — 52px bar: breadcrumb (`title · context`), a ⌘K trigger button (calls `onOpenPalette`), an optional `＋ New` primary button, a theme toggle button (calls `onCycleTheme`, shows `themeLabel`), and the user label.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/ui/Topbar.test.tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { Topbar } from './Topbar';

afterEach(cleanup);

describe('Topbar', () => {
  it('shows the title and opens the palette on the search trigger', () => {
    const onOpenPalette = vi.fn();
    render(<Topbar title="Dashboard" context="Command Center" onOpenPalette={onOpenPalette}
      onCycleTheme={vi.fn()} themeLabel="System" userLabel="AR" />);
    expect(screen.getByText('Dashboard')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /search or jump to/i }));
    expect(onOpenPalette).toHaveBeenCalledOnce();
  });
  it('renders and fires the New action when provided', () => {
    const onNew = vi.fn();
    render(<Topbar title="Students" onOpenPalette={vi.fn()} onNew={onNew}
      onCycleTheme={vi.fn()} themeLabel="Dark" userLabel="AR" newLabel="New student" />);
    fireEvent.click(screen.getByRole('button', { name: 'New student' }));
    expect(onNew).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/ui/Topbar.test.tsx`
Expected: FAIL — cannot find `./Topbar`.

- [ ] **Step 3: Append Topbar CSS to `ui-kit.css`**

```css
/* Topbar */
.ck-topbar { height:52px; display:flex; align-items:center; gap:14px; padding:0 20px;
  background:var(--bg-surface); border-bottom:1px solid var(--border-subtle); }
.ck-topbar-crumb { font-size:13px; font-weight:600; color:var(--text-primary); }
.ck-topbar-crumb span { color:var(--text-muted); font-weight:500; }
.ck-topbar-grow { flex:1; }
.ck-topbar-kbd { display:flex; align-items:center; gap:7px; font-size:11.5px; color:var(--text-muted);
  border:1px solid var(--border-default); border-radius:var(--r-sm); padding:5px 10px; background:var(--bg-canvas); cursor:pointer; }
.ck-topbar-kbd b { color:var(--text-secondary); font-weight:700; }
.ck-topbar-icon { height:30px; padding:0 10px; border-radius:var(--r-sm); border:1px solid var(--border-default);
  background:var(--bg-surface); color:var(--text-secondary); font-size:12px; cursor:pointer; }
```

- [ ] **Step 4: Implement `Topbar.tsx`**

```tsx
// frontend/src/components/ui/Topbar.tsx
import { Button } from './Button';

interface TopbarProps {
  title: string;
  context?: string;
  onOpenPalette: () => void;
  onNew?: () => void;
  newLabel?: string;
  onCycleTheme: () => void;
  themeLabel: string;
  userLabel: string;
}

export function Topbar({ title, context, onOpenPalette, onNew, newLabel = 'New', onCycleTheme, themeLabel, userLabel }: TopbarProps) {
  return (
    <header className="ck-topbar">
      <div className="ck-topbar-crumb">{title}{context && <span> · {context}</span>}</div>
      <div className="ck-topbar-grow" />
      <button className="ck-topbar-kbd" onClick={onOpenPalette} aria-label="Search or jump to">
        <b>⌘K</b> Search or jump to…
      </button>
      {onNew && <Button size="sm" onClick={onNew}>＋ {newLabel}</Button>}
      <button className="ck-topbar-icon" onClick={onCycleTheme} aria-label={`Theme: ${themeLabel}`}>{themeLabel}</button>
      <span className="ck-topbar-icon" aria-hidden="true">{userLabel}</span>
    </header>
  );
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd frontend && npx vitest run src/components/ui/Topbar.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ui/Topbar.tsx frontend/src/components/ui/Topbar.test.tsx frontend/src/styles/ui-kit.css
git commit -m "feat(design): Topbar (breadcrumb, ⌘K, New, theme toggle)"
```

---

### Task 13: Adopt the shell in `UnifiedWorkspacePage` (visual only)

**Files:**
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx`

**Interfaces:**
- Consumes: `NavRail`, `Topbar`, `CommandPalette`, `useTheme`, `useDensity`, the existing state in the page (`panel`, `setPanel`, `openGroups`, `toggleGroup`, the role→sections selection, `PANEL_TITLES`, `user`, `saInvBadge`).
- Produces: no new exports. Replaces the hand-rolled sidebar/topbar JSX with the new components and mounts the command palette + a global ⌘K listener. **No panel-state logic is moved or removed.**

> This is the highest-risk task (the file is 600+ lines and owns all panel state). Touch only the shell render blocks and add the palette. Do not refactor panel state. If existing snapshot/behaviour tests fail, restore the specific behaviour rather than deleting the test.

- [ ] **Step 1: Add hook + palette state near the other `useState` declarations**

In `UnifiedWorkspacePage.tsx`, after the existing `const { can } = usePermissions();` block and existing state, add:

```tsx
import { NavRail } from '../components/ui/NavRail';
import { Topbar } from '../components/ui/Topbar';
import { CommandPalette } from '../components/ui/CommandPalette';
import type { CommandItem } from '../components/ui/fuzzy';
import { useTheme } from '../hooks/useTheme';
import { useDensity } from '../hooks/useDensity';
```

```tsx
const { theme, setTheme } = useTheme();
useDensity(); // stamps data-density; density UI can be added later
const [paletteOpen, setPaletteOpen] = useState(false);

useEffect(() => {
  const onKey = (e: KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPaletteOpen(v => !v); }
  };
  window.addEventListener('keydown', onKey);
  return () => window.removeEventListener('keydown', onKey);
}, []);

const cycleTheme = () => setTheme(theme === 'system' ? 'light' : theme === 'light' ? 'dark' : 'system');
const themeLabel = theme === 'system' ? 'Auto' : theme === 'light' ? 'Light' : 'Dark';
```

- [ ] **Step 2: Build the nav sections + palette items from existing role logic**

Find where the role→sections array is chosen (the `ADMIN_NAV_SECTIONS` / `TEACHER_NAV_SECTIONS` / … selection). Assign it to a local `navSections`. Then derive palette items and badges:

```tsx
const paletteItems: CommandItem[] = navSections.flatMap(sec =>
  sec.items.map(item => ({ id: item.key, label: item.label, group: sec.title, onSelect: () => setPanel(item.key) }))
);
const badges: Partial<Record<PanelKey, { count: number; urgent?: boolean }>> =
  saInvBadge > 0 ? { 'sa-invoices': { count: saInvBadge } } : {};
```

- [ ] **Step 3: Replace the sidebar JSX with `NavRail`**

Locate the existing `<aside>`/sidebar render block (the hand-rolled nav using `openGroups`, `toggleGroup`, `NavIcon`). Replace that block with:

```tsx
<NavRail
  sections={navSections}
  activePanel={panel}
  onSelect={setPanel}
  openGroups={openGroups}
  onToggleGroup={toggleGroup}
  schoolName={workspace?.schoolName ?? user?.branchName ?? 'Custoking IMS'}
  userLabel={user?.name ?? 'Account'}
  badges={badges}
/>
```

Keep the existing mobile toggle (`sidebarOpen`) wrapper if present; only the inner nav markup is replaced.

- [ ] **Step 4: Replace the topbar JSX with `Topbar` and mount the palette**

Locate the existing top header block that renders the panel title. Replace with:

```tsx
<Topbar
  title={PANEL_TITLES[panel]}
  onOpenPalette={() => setPaletteOpen(true)}
  onCycleTheme={cycleTheme}
  themeLabel={themeLabel}
  userLabel={(user?.name ?? 'AC').slice(0, 2).toUpperCase()}
/>
```

Add the palette once, near the root return (e.g. just before the closing fragment):

```tsx
<CommandPalette open={paletteOpen} items={paletteItems} onClose={() => setPaletteOpen(false)} />
```

- [ ] **Step 5: Run the existing workspace/page tests + typecheck**

Run: `cd frontend && npx vitest run && npx tsc --noEmit`
Expected: all existing tests PASS; 0 TS errors. If a test asserted old sidebar markup, update it to assert the new `NavRail`/`Topbar` output (do not delete coverage).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/UnifiedWorkspacePage.tsx frontend/src/pages/workspace/*.test.tsx
git commit -m "feat(design): adopt NavRail/Topbar/CommandPalette in workspace shell"
```

---

## Phase D — Verification

### Task 14: Full build, test sweep, and manual smoke

**Files:** none (verification only)

- [ ] **Step 1: Full test suite**

Run: `cd frontend && npm test`
Expected: all suites PASS (new: theme, useTheme, useDensity, formatDelta, StatusPill, Button, StatCard, SidePanel, fuzzy, CommandPalette, NavRail, Topbar).

- [ ] **Step 2: Production build**

Run: `cd frontend && npm run build`
Expected: `tsc` clean, Vite build succeeds, `dist/assets` includes `ibm-plex-sans-*.woff2`.

- [ ] **Step 3: Manual visual + a11y smoke (dev server)**

Run: `cd frontend && npm run dev`, log in as an admin, and confirm against this checklist:
- Navy rail renders; active item highlighted; group headers collapse/expand; badges show.
- Topbar shows breadcrumb + ⌘K trigger; pressing **⌘K / Ctrl-K** opens the palette; typing filters; Enter navigates; Esc closes.
- Theme toggle cycles Auto → Light → Dark; dark surface is `#181B21` (not pure black); text stays legible.
- Tab through the rail and topbar: every focused control shows the 2px indigo focus ring.
- A data-heavy panel (Students/Fees) still renders (legacy `--ck-*`/shorthand consumers now show the new palette with no markup change).
- With OS "reduce motion" on, the palette/side-panel appear without animation.

- [ ] **Step 4: Commit any smoke fixes, then tag the foundation**

```bash
git add -A
git commit -m "chore(design): foundation verification fixes" || echo "nothing to fix"
```

---

## Self-review

**Spec coverage:**
- §2 color system → Task 1 (all tokens incl. rail/status/chart, light+dark). ✓
- §2.6 validated chart palettes → Task 1 (`--chart-1..6` both modes, verbatim). ✓
- §3 typography (Plex, tabular) → Task 3. ✓
- §4 spacing/radius/density/elevation/focus/motion → Task 1 (tokens) + Task 8 (density). ✓
- §5.1 nav rail → Task 11; §5.2 topbar → Task 12; §5.3 stat card → Task 7; §5.5 buttons → Task 6; §5.6 status pill → Task 5; §5.8 empty/loading → **reused** existing `EmptyState`/`skeleton.css`/`DataTable` (no task needed; noted in File structure); §5.9 command palette → Task 10; §5.10 side panel → Task 9. ✓
- §6 token delivery / redefine `--ck-*` / no markup change → Task 1 + Task 13 (shell adopts visually only). ✓
- §5.4 data table (frozen col, right-align, side-panel edit, pagination) → **partially** foundational here (density token + reused DataTable + SidePanel component exist); the per-column frozen/right-align/pagination wiring is applied during the **per-module** table redesign specs (out of scope per spec §7). Flagged intentionally, not a gap.
- §8 acceptance → Task 14 checklist. ✓

**Placeholder scan:** no TBD/TODO; every code step contains full code; every run step states expected output. ✓

**Type consistency:** `Delta` (Task 4) consumed by `StatCard` (Task 7); `CommandItem` (Task 10) consumed by `CommandPalette` + shell (Task 13); `PanelKey` from `config.ts` used by `NavRail`; `ThemeChoice` from `useTheme` used by shell. Class names consistent (`ck-btn2--*`, `ck-pill2--*`, `ck-rail-item--active`, `ck-delta--{direction}`). ✓

**Note on TDD for CSS-only Task 1:** pure token files can't be behaviourally unit-tested; the `theme.test.ts` regression guard (asserting the spec hex values + reduced-motion block exist) is the appropriate substitute, backed by the Task 14 visual smoke.
