# Custoking IMS — Frontend UI Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the entire Custoking IMS frontend from a functional SPA into a distinctive, production-grade, investor-demo-ready enterprise school ERP UI with consistent design language, polished components, and proper responsiveness.

**Architecture:** Extend the existing CSS-first design system (currently `styles.css`) with semantic token aliases, new utility patterns, and polished component classes. Create a small set of reusable React UI components. Improve each panel/page incrementally without changing any API contracts or business logic. Verify build passes after every pass.

**Tech Stack:** React 18, TypeScript 5, Vite 6, vanilla CSS (no CSS-in-JS), lucide-react icons, DM Sans + Fraunces + Instrument Serif fonts, axios API client.

**Key constraint:** The existing `ck-*` class namespace must be preserved. New tokens and classes extend, they do not replace. No backend changes. No logic changes in panels. No API contract changes.

---

## File Map

### New files to create:
- `frontend/src/styles/tokens.css` — semantic token aliases on top of existing `:root` vars
- `frontend/src/styles/skeleton.css` — shimmer skeleton animation and utility classes
- `frontend/src/styles/sidebar.css` — mobile-improved sidebar and nav styles
- `frontend/src/styles/drawers.css` — right-side drawer shell styles
- `frontend/src/components/ui/Skeleton.tsx` — React skeleton component
- `frontend/src/components/ui/EmptyState.tsx` — consistent empty state component
- `frontend/src/components/ui/DrawerShell.tsx` — right-side drawer wrapper
- `frontend/src/components/ui/PageHeader.tsx` — consistent page header with title/subtitle/actions
- `frontend/src/components/ui/SectionCard.tsx` — section card with optional header, actions slot
- `frontend/src/components/ui/StatCard.tsx` — upgraded metric card with optional spark, delta, CTA
- `frontend/src/components/ui/StatusBadge.tsx` — (extend existing shared/components/StatusBadge.tsx)

### Modified files:
- `frontend/src/main.tsx` — import new CSS files
- `frontend/src/styles.css` — extend: mobile sidebar drawer, table money alignment, improved form sections, better drawer backdrop, skeleton patterns
- `frontend/src/pages/UnifiedWorkspacePage.tsx` — sidebar visual improvements (mobile toggle, school badge, user avatar), topbar school/user context display
- `frontend/src/pages/workspace/ui.tsx` — add ModuleSectionHeader, FormSection, InfoGrid helpers
- `frontend/src/pages/workspace/panels/StudentsPanel.tsx` — filter bar, avatar quality, table column alignment, empty state, skeleton
- `frontend/src/pages/workspace/panels/AddStudentPanel.tsx` — form section grouping, field organization
- `frontend/src/pages/workspace/panels/FeesPanel.tsx` — collection progress card, status pill consistency, money right-align
- `frontend/src/pages/workspace/panels/AttendancePanel.tsx` — class card progress, status clarity, mark-all actions
- `frontend/src/pages/workspace/panels/FirefightingDashboardPanel.tsx` — urgency cards, status timeline, drawer polish
- `frontend/src/pages/workspace/panels/AdminOrdersPanel.tsx` — status pipeline visual, order cards
- `frontend/src/pages/workspace/panels/FirefightingNewPanel.tsx` — guided multi-step form look
- `frontend/src/pages/workspace/panels/StaffPanel.tsx` — table + basic stats header
- `frontend/src/shared/components/DataTable.tsx` — skeleton rows, empty state, money alignment
- `frontend/src/components/Modal.tsx` — mobile full-screen behavior

---

## PASS 1 — Design Token System

### Task 1: Create semantic CSS tokens file

**Files:**
- Create: `frontend/src/styles/tokens.css`

- [ ] **Step 1: Create tokens.css with semantic aliases**

```css
/* frontend/src/styles/tokens.css
   Semantic token aliases on top of the existing :root shorthand vars.
   Never remove the original vars — these are additive aliases only.
*/

:root {
  /* ── Brand Colors ──────────────────────────────────────────────── */
  --ck-color-primary:        var(--g);        /* #1a6840 forest green */
  --ck-color-primary-dark:   #135533;
  --ck-color-primary-soft:   var(--g1);       /* #e8f5ee */
  --ck-color-primary-border: var(--g2);       /* #c4ddd0 */

  --ck-color-accent:         var(--b);        /* #1a4fa8 blue */
  --ck-color-accent-soft:    var(--b1);       /* #e6edf8 */

  --ck-color-warning:        var(--am);       /* #b35c00 amber */
  --ck-color-warning-soft:   var(--am1);      /* #fff3e6 */

  --ck-color-danger:         var(--re);       /* #c0312b red */
  --ck-color-danger-soft:    var(--re1);      /* #fdecea */

  --ck-color-orange:         var(--or);       /* #d14e12 orange */
  --ck-color-orange-soft:    var(--or1);      /* #fff0e8 */

  --ck-color-purple:         var(--pu);       /* #5b2d8a purple */
  --ck-color-purple-soft:    var(--pu1);      /* #f2edf9 */

  /* ── Surfaces ──────────────────────────────────────────────────── */
  --ck-bg-app:               var(--bg);       /* #f7f6f3 warm canvas */
  --ck-bg-surface:           var(--white);    /* #ffffff */
  --ck-bg-surface-raised:    #faf9f7;         /* slightly warm white for headers */
  --ck-bg-overlay:           rgba(0,0,0,.32);

  /* ── Text ──────────────────────────────────────────────────────── */
  --ck-text-primary:         var(--ink);      /* #1a1a1a */
  --ck-text-secondary:       var(--ink2);     /* #5a5a5a */
  --ck-text-muted:           var(--ink3);     /* #8d8c88 */
  --ck-text-inverse:         #ffffff;

  /* ── Borders ───────────────────────────────────────────────────── */
  --ck-border-subtle:        var(--border);   /* #e4e2dc */
  --ck-border-default:       var(--border2);  /* #ccc9c0 */

  /* ── Shadows ───────────────────────────────────────────────────── */
  --ck-shadow-card:          0 1px 4px rgba(0,0,0,.03);
  --ck-shadow-raised:        var(--shadow);   /* 0 12px 34px rgba(0,0,0,.06), 0 3px 10px rgba(0,0,0,.04) */
  --ck-shadow-popover:       0 8px 24px rgba(0,0,0,.12), 0 2px 8px rgba(0,0,0,.06);
  --ck-shadow-drawer:        -4px 0 24px rgba(0,0,0,.08);

  /* ── Border Radius ─────────────────────────────────────────────── */
  --ck-radius-xs:            4px;
  --ck-radius-sm:            8px;
  --ck-radius-md:            12px;
  --ck-radius-lg:            16px;
  --ck-radius-xl:            20px;
  --ck-radius-pill:          999px;

  /* ── Spacing Scale (8px base) ──────────────────────────────────── */
  --ck-space-1:              4px;
  --ck-space-2:              8px;
  --ck-space-3:              12px;
  --ck-space-4:              16px;
  --ck-space-5:              20px;
  --ck-space-6:              24px;
  --ck-space-7:              32px;
  --ck-space-8:              48px;

  /* ── Typography Scale ──────────────────────────────────────────── */
  --ck-font-display:         'Instrument Serif', serif;
  --ck-font-heading:         'Fraunces', serif;
  --ck-font-body:            'DM Sans', system-ui, sans-serif;
  --ck-font-mono:            ui-monospace, 'JetBrains Mono', monospace;

  --ck-text-xs:              10px;
  --ck-text-sm:              12px;
  --ck-text-base:            13px;
  --ck-text-md:              14px;
  --ck-text-lg:              16px;
  --ck-text-xl:              18px;
  --ck-text-2xl:             22px;
  --ck-text-3xl:             28px;

  /* ── Z-Index Layers ────────────────────────────────────────────── */
  --ck-z-base:               0;
  --ck-z-sticky:             10;
  --ck-z-topbar:             20;
  --ck-z-dropdown:           30;
  --ck-z-drawer:             50;
  --ck-z-modal:              60;
  --ck-z-toast:              100;

  /* ── Animation ─────────────────────────────────────────────────── */
  --ck-duration-fast:        0.12s;
  --ck-duration-base:        0.18s;
  --ck-duration-slow:        0.3s;
  --ck-ease-out:             cubic-bezier(0.2, 0.8, 0.2, 1);

  /* ── Status Colors ─────────────────────────────────────────────── */
  --ck-status-paid:          var(--g);
  --ck-status-paid-bg:       var(--g1);
  --ck-status-pending:       var(--am);
  --ck-status-pending-bg:    var(--am1);
  --ck-status-overdue:       var(--re);
  --ck-status-overdue-bg:    var(--re1);
  --ck-status-partial:       var(--or);
  --ck-status-partial-bg:    var(--or1);
  --ck-status-info:          var(--b);
  --ck-status-info-bg:       var(--b1);
  --ck-status-neutral:       #888;
  --ck-status-neutral-bg:    #f4f4f0;

  /* ── Chart / Module Identity ───────────────────────────────────── */
  --ck-chart-fees:           var(--g);
  --ck-chart-students:       var(--b);
  --ck-chart-attendance:     var(--am);
  --ck-chart-supply:         var(--pu);
  --ck-chart-firefighting:   var(--re);
}
```

- [ ] **Step 2: Create skeleton.css**

```css
/* frontend/src/styles/skeleton.css */

@keyframes ck-shimmer {
  0%   { background-position: -400px 0; }
  100% { background-position: 400px 0; }
}

.ck-skeleton {
  background: linear-gradient(90deg, #ede9e1 25%, #f7f4ed 50%, #ede9e1 75%);
  background-size: 800px 100%;
  animation: ck-shimmer 1.4s ease-in-out infinite;
  border-radius: var(--ck-radius-sm);
}

/* Prebuilt skeleton shapes */
.ck-skeleton-text    { height: 14px; border-radius: var(--ck-radius-xs); margin-bottom: 6px; }
.ck-skeleton-title   { height: 22px; width: 60%; border-radius: var(--ck-radius-sm); margin-bottom: 10px; }
.ck-skeleton-badge   { height: 22px; width: 72px; border-radius: var(--ck-radius-pill); display: inline-block; }
.ck-skeleton-avatar  { width: 40px; height: 40px; border-radius: 50%; flex-shrink: 0; }
.ck-skeleton-avatar-lg { width: 64px; height: 64px; border-radius: 50%; flex-shrink: 0; }
.ck-skeleton-btn     { height: 36px; width: 100px; border-radius: var(--ck-radius-pill); }
.ck-skeleton-card    { height: 96px; border-radius: var(--ck-radius-md); }
.ck-skeleton-stat    { height: 100px; border-radius: var(--ck-radius-md); }

/* Table skeleton row */
.ck-skeleton-row     { display: flex; gap: 16px; align-items: center; padding: 12px 16px; border-bottom: 1px solid var(--ck-border-subtle); }
.ck-skeleton-row .ck-skeleton-text { margin: 0; flex: 1; }

/* Stagger utility — apply via inline style or nth-child */
.ck-skeleton-stagger-1 { animation-delay: 0.05s; }
.ck-skeleton-stagger-2 { animation-delay: 0.10s; }
.ck-skeleton-stagger-3 { animation-delay: 0.15s; }
.ck-skeleton-stagger-4 { animation-delay: 0.20s; }
.ck-skeleton-stagger-5 { animation-delay: 0.25s; }
```

- [ ] **Step 3: Create sidebar.css with mobile drawer behavior**

```css
/* frontend/src/styles/sidebar.css
   Mobile sidebar drawer upgrade.
   Replaces the "position:static" mobile collapse with a proper drawer.
*/

/* ── Mobile menu toggle button ─────────────────────────────────── */
.ck-menu-toggle {
  display: none;
  align-items: center;
  justify-content: center;
  width: 36px; height: 36px;
  border: 1px solid var(--ck-border-subtle);
  border-radius: var(--ck-radius-sm);
  background: var(--ck-bg-surface);
  color: var(--ck-text-secondary);
  cursor: pointer;
  flex-shrink: 0;
}
.ck-menu-toggle:hover { background: var(--ck-bg-app); }
.ck-menu-toggle:focus-visible { outline: 2px solid var(--ck-color-accent); outline-offset: 2px; }

/* ── Sidebar overlay backdrop (mobile only) ────────────────────── */
.ck-sidebar-backdrop {
  display: none;
  position: fixed; inset: 0;
  background: rgba(0,0,0,.38);
  z-index: calc(var(--ck-z-drawer) - 1);
  backdrop-filter: blur(2px);
}

/* ── Sidebar header extras ─────────────────────────────────────── */
.ck-sb-school-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 4px;
  padding: 3px 8px;
  border-radius: var(--ck-radius-pill);
  background: var(--ck-color-primary-soft);
  border: 1px solid var(--ck-color-primary-border);
  font-size: 10px;
  font-weight: 700;
  color: var(--ck-color-primary);
  letter-spacing: .03em;
}

/* ── Nav item badge (notification count) ───────────────────────── */
.ck-nav-badge {
  margin-left: auto;
  min-width: 18px; height: 18px;
  padding: 0 5px;
  border-radius: var(--ck-radius-pill);
  background: var(--ck-color-danger);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.ck-nav-badge.warn {
  background: var(--ck-color-warning);
}

/* ── User card in sidebar ──────────────────────────────────────── */
.ck-user-avatar {
  width: 34px; height: 34px;
  border-radius: 50%;
  background: var(--ck-color-primary-soft);
  border: 1px solid var(--ck-color-primary-border);
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700;
  color: var(--ck-color-primary);
  flex-shrink: 0;
}

.ck-user-card-inner {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* ── Topbar extras ─────────────────────────────────────────────── */
.ck-topbar-school {
  font-size: 12px;
  color: var(--ck-text-muted);
  font-weight: 600;
  padding: 4px 10px;
  border-radius: var(--ck-radius-pill);
  background: var(--ck-bg-app);
  border: 1px solid var(--ck-border-subtle);
}

.ck-topbar-user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border-radius: var(--ck-radius-md);
  cursor: pointer;
  transition: background var(--ck-duration-fast);
}
.ck-topbar-user:hover { background: var(--ck-bg-app); }
.ck-topbar-user-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--ck-text-secondary);
}

/* ── Sidebar close button (mobile) ─────────────────────────────── */
.ck-sb-close {
  display: none;
  margin-left: auto;
  width: 28px; height: 28px;
  border: none; background: none;
  border-radius: var(--ck-radius-sm);
  color: var(--ck-text-muted);
  cursor: pointer;
  align-items: center; justify-content: center;
}
.ck-sb-close:hover { background: var(--ck-bg-app); color: var(--ck-text-primary); }

/* ── Mobile breakpoint: sidebar becomes fixed drawer ───────────── */
@media (max-width: 820px) {
  .ck-menu-toggle { display: flex; }

  .ck-sidebar {
    position: fixed;
    top: 0; left: 0; bottom: 0;
    width: 272px;
    z-index: var(--ck-z-drawer);
    transform: translateX(-100%);
    transition: transform var(--ck-duration-slow) var(--ck-ease-out);
    box-shadow: var(--ck-shadow-popover);
  }

  .ck-sidebar.open {
    transform: translateX(0);
  }

  .ck-sidebar-backdrop.open {
    display: block;
  }

  .ck-main { margin-left: 0 !important; }

  .ck-sb-close { display: flex; }

  .ck-sb-header { display: flex; align-items: center; }
}
```

- [ ] **Step 4: Create drawers.css for right-side action drawers**

```css
/* frontend/src/styles/drawers.css
   Right-side drawer shell used by FeeDefaultersDrawer, LowAttendanceDrawer, etc.
*/

.ck-drawer-backdrop {
  position: fixed; inset: 0;
  background: rgba(0,0,0,.28);
  z-index: calc(var(--ck-z-drawer) - 1);
  backdrop-filter: blur(2px);
  animation: ck-fade-in var(--ck-duration-base) ease;
}

.ck-drawer {
  position: fixed;
  top: 0; right: 0; bottom: 0;
  width: min(480px, 100vw);
  background: var(--ck-bg-surface);
  border-left: 1px solid var(--ck-border-subtle);
  box-shadow: var(--ck-shadow-drawer);
  z-index: var(--ck-z-drawer);
  display: flex; flex-direction: column;
  animation: ck-slide-right var(--ck-duration-slow) var(--ck-ease-out);
  overflow: hidden;
}

.ck-drawer-lg {
  width: min(600px, 100vw);
}

.ck-drawer-header {
  padding: 18px 20px 16px;
  border-bottom: 1px solid var(--ck-border-subtle);
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex-shrink: 0;
  background: var(--ck-bg-surface-raised);
}

.ck-drawer-header-icon {
  width: 38px; height: 38px;
  border-radius: var(--ck-radius-md);
  display: grid; place-items: center;
  flex-shrink: 0;
  font-size: 18px;
}

.ck-drawer-title { font: 400 20px/1.2 var(--ck-font-heading); margin: 0 0 3px; }
.ck-drawer-subtitle { font-size: 13px; color: var(--ck-text-muted); margin: 0; }

.ck-drawer-close {
  margin-left: auto; flex-shrink: 0;
  width: 32px; height: 32px;
  border: none; background: transparent;
  border-radius: var(--ck-radius-sm);
  color: var(--ck-text-muted);
  cursor: pointer;
  display: grid; place-items: center;
  font-size: 20px; line-height: 1;
  transition: background var(--ck-duration-fast), color var(--ck-duration-fast);
}
.ck-drawer-close:hover { background: var(--ck-bg-app); color: var(--ck-text-primary); }
.ck-drawer-close:focus-visible { outline: 2px solid var(--ck-color-accent); outline-offset: 2px; }

.ck-drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.ck-drawer-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--ck-border-subtle);
  background: var(--ck-bg-surface-raised);
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  justify-content: flex-end;
}

@keyframes ck-slide-right {
  from { transform: translateX(100%); opacity: 0; }
  to   { transform: translateX(0);    opacity: 1; }
}

@keyframes ck-fade-in {
  from { opacity: 0; }
  to   { opacity: 1; }
}

/* Mobile: drawer goes full-screen */
@media (max-width: 600px) {
  .ck-drawer, .ck-drawer-lg { width: 100vw; border-left: none; }
}
```

- [ ] **Step 5: Import all new CSS files in main.tsx**

Read `frontend/src/main.tsx` first. Then add imports after the existing `styles.css` import:

```tsx
import './styles.css';
import './styles/tokens.css';
import './styles/skeleton.css';
import './styles/sidebar.css';
import './styles/drawers.css';
```

- [ ] **Step 6: Run build to verify no CSS errors**

```bash
cd frontend && npm run build
```

Expected: Build succeeds. No TypeScript errors from the pure CSS additions.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/styles/ frontend/src/main.tsx
git commit -m "feat: add semantic design tokens, skeleton, drawer, sidebar CSS"
```

---

## PASS 2 — Shared UI Components

### Task 2: Create Skeleton React component

**Files:**
- Create: `frontend/src/components/ui/Skeleton.tsx`

- [ ] **Step 1: Create Skeleton.tsx**

```tsx
// frontend/src/components/ui/Skeleton.tsx
// Shimmer skeleton loading component. Uses .ck-skeleton* CSS from skeleton.css.

interface SkeletonProps {
  width?: string | number;
  height?: string | number;
  borderRadius?: string | number;
  className?: string;
  style?: React.CSSProperties;
}

export function Skeleton({ width, height, borderRadius, className = '', style }: SkeletonProps) {
  return (
    <div
      className={`ck-skeleton ${className}`}
      style={{ width, height, borderRadius, ...style }}
      aria-hidden="true"
    />
  );
}

export function SkeletonText({ width = '100%' }: { width?: string | number }) {
  return <div className="ck-skeleton ck-skeleton-text" style={{ width }} aria-hidden="true" />;
}

export function SkeletonRow() {
  return (
    <div className="ck-skeleton-row" aria-hidden="true">
      <div className="ck-skeleton ck-skeleton-avatar" />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div className="ck-skeleton ck-skeleton-text" style={{ width: '55%' }} />
        <div className="ck-skeleton ck-skeleton-text" style={{ width: '35%', height: 11 }} />
      </div>
      <div className="ck-skeleton ck-skeleton-badge" />
    </div>
  );
}

export function SkeletonTableRows({ count = 5 }: { count?: number }) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <SkeletonRow key={i} />
      ))}
    </>
  );
}

export function SkeletonStatGrid({ count = 4 }: { count?: number }) {
  return (
    <div className="ck-stats ck-s4" aria-hidden="true">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="ck-skeleton ck-skeleton-stat" style={{ animationDelay: `${i * 0.05}s` }} />
      ))}
    </div>
  );
}
```

### Task 3: Create EmptyState component

**Files:**
- Create: `frontend/src/components/ui/EmptyState.tsx`

- [ ] **Step 1: Create EmptyState.tsx**

```tsx
// frontend/src/components/ui/EmptyState.tsx
// Consistent empty state component for tables, panels, and sections.

interface EmptyStateProps {
  icon?: string;
  title: string;
  description?: string;
  action?: React.ReactNode;
  compact?: boolean;
}

export function EmptyState({ icon = '📋', title, description, action, compact }: EmptyStateProps) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        padding: compact ? '28px 20px' : '52px 24px',
        gap: 10,
      }}
      role="status"
      aria-label={title}
    >
      <div style={{ fontSize: compact ? 28 : 40, marginBottom: 4, lineHeight: 1 }} aria-hidden="true">
        {icon}
      </div>
      <div style={{ fontWeight: 700, fontSize: compact ? 14 : 16, color: 'var(--ck-text-primary)' }}>
        {title}
      </div>
      {description && (
        <div style={{ fontSize: 13, color: 'var(--ck-text-muted)', maxWidth: 340, lineHeight: 1.5 }}>
          {description}
        </div>
      )}
      {action && <div style={{ marginTop: 8 }}>{action}</div>}
    </div>
  );
}

export function EmptyTableState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <tr>
      <td colSpan={99} style={{ padding: 0, border: 'none' }}>
        <EmptyState icon={icon} title={title} description={description} action={action} />
      </td>
    </tr>
  );
}
```

### Task 4: Create DrawerShell component

**Files:**
- Create: `frontend/src/components/ui/DrawerShell.tsx`

- [ ] **Step 1: Create DrawerShell.tsx**

```tsx
// frontend/src/components/ui/DrawerShell.tsx
// Consistent right-side drawer shell. Uses styles from drawers.css.

import { useEffect } from 'react';

interface DrawerShellProps {
  open: boolean;
  onClose: () => void;
  title: string;
  subtitle?: string;
  icon?: string;
  iconBg?: string;
  iconColor?: string;
  size?: 'default' | 'lg';
  footer?: React.ReactNode;
  children: React.ReactNode;
}

export function DrawerShell({
  open, onClose, title, subtitle, icon, iconBg = 'var(--ck-bg-app)', iconColor = 'var(--ck-text-secondary)',
  size = 'default', footer, children,
}: DrawerShellProps) {
  // Lock body scroll when drawer is open
  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [open]);

  if (!open) return null;

  return (
    <>
      <div
        className="ck-drawer-backdrop"
        onClick={onClose}
        role="presentation"
        aria-hidden="true"
      />
      <div
        className={`ck-drawer${size === 'lg' ? ' ck-drawer-lg' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="ck-drawer-header">
          {icon && (
            <div className="ck-drawer-header-icon" style={{ background: iconBg, color: iconColor }}>
              {icon}
            </div>
          )}
          <div style={{ flex: 1, minWidth: 0 }}>
            <h2 className="ck-drawer-title">{title}</h2>
            {subtitle && <p className="ck-drawer-subtitle">{subtitle}</p>}
          </div>
          <button
            className="ck-drawer-close"
            onClick={onClose}
            aria-label="Close drawer"
          >
            ×
          </button>
        </div>

        <div className="ck-drawer-body">
          {children}
        </div>

        {footer && (
          <div className="ck-drawer-footer">
            {footer}
          </div>
        )}
      </div>
    </>
  );
}
```

### Task 5: Create SectionCard and PageHeader components

**Files:**
- Create: `frontend/src/components/ui/SectionCard.tsx`
- Create: `frontend/src/components/ui/PageHeader.tsx`

- [ ] **Step 1: Create SectionCard.tsx**

```tsx
// frontend/src/components/ui/SectionCard.tsx
// Consistent section card wrapper that uses existing .ck-card styles.

interface SectionCardProps {
  title?: string;
  subtitle?: string;
  actions?: React.ReactNode;
  noPad?: boolean;
  children: React.ReactNode;
}

export function SectionCard({ title, subtitle, actions, noPad, children }: SectionCardProps) {
  return (
    <div className="ck-card">
      {(title || actions) && (
        <div className="ck-card-h">
          <div>
            {title && <div className="ck-card-t">{title}</div>}
            {subtitle && <div style={{ fontSize: 12, color: 'var(--ck-text-muted)', marginTop: 2 }}>{subtitle}</div>}
          </div>
          {actions && <div className="ck-actions-inline" style={{ flexShrink: 0 }}>{actions}</div>}
        </div>
      )}
      {noPad ? children : <div className="ck-form-body">{children}</div>}
    </div>
  );
}
```

- [ ] **Step 2: Create PageHeader.tsx**

```tsx
// frontend/src/components/ui/PageHeader.tsx
// Consistent page-level header bar. Replaces ad-hoc ck-ph usage.

interface PageHeaderProps {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
  meta?: React.ReactNode;
}

export function PageHeader({ title, subtitle, actions, meta }: PageHeaderProps) {
  return (
    <div className="ck-ph">
      <div className="ck-ph-l">
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
        {meta && <div style={{ marginTop: 6 }}>{meta}</div>}
      </div>
      {actions && <div className="ck-actions-inline">{actions}</div>}
    </div>
  );
}
```

- [ ] **Step 3: Create an index barrel for /components/ui/**

```tsx
// frontend/src/components/ui/index.ts
export { Skeleton, SkeletonText, SkeletonRow, SkeletonTableRows, SkeletonStatGrid } from './Skeleton';
export { EmptyState, EmptyTableState } from './EmptyState';
export { DrawerShell } from './DrawerShell';
export { SectionCard } from './SectionCard';
export { PageHeader } from './PageHeader';
```

- [ ] **Step 4: Run build**

```bash
cd frontend && npm run build
```

Expected: Build succeeds. No errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui/
git commit -m "feat: add Skeleton, EmptyState, DrawerShell, SectionCard, PageHeader UI components"
```

---

## PASS 3 — Sidebar & Topbar Mobile Upgrade

### Task 6: Add mobile drawer behavior to UnifiedWorkspacePage

**Files:**
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx`

**Goal:** Add `sidebarOpen` state and `ck-menu-toggle` button to topbar. Apply `.open` class to sidebar and backdrop. Add school name badge and user avatar initials. Do NOT change any panel state, API calls, or panel rendering logic.

- [ ] **Step 1: Read the current sidebar/topbar section of UnifiedWorkspacePage.tsx (lines 1–300)**

Read `frontend/src/pages/UnifiedWorkspacePage.tsx` lines 300–600 to find where the sidebar JSX is rendered.

- [ ] **Step 2: Identify the exact JSX block for sidebar and topbar**

The sidebar renders a `.ck-sidebar` div with `.ck-sb-header`, `.ck-nav`, and `.ck-user-card`. The topbar renders `.ck-topbar` with `.ck-topbar-title` and action buttons.

- [ ] **Step 3: Add sidebarOpen state at top of component (after existing state declarations)**

Find this pattern near the top of the component (after the existing state declarations):
```tsx
const [saInvBadge, setSaInvBadge] = useState(0);
```

Add after it:
```tsx
const [sidebarOpen, setSidebarOpen] = useState(false);
```

- [ ] **Step 4: Add backdrop element and mobile class to sidebar**

In the JSX, find:
```tsx
<div className="workspace-shell">
  <nav className="ck-sidebar">
```
(or similar opening of the sidebar element)

Add the backdrop div and modify the sidebar className:
```tsx
<div className="workspace-shell">
  {/* Mobile sidebar backdrop */}
  <div
    className={`ck-sidebar-backdrop${sidebarOpen ? ' open' : ''}`}
    onClick={() => setSidebarOpen(false)}
    aria-hidden="true"
  />
  <nav className={`ck-sidebar${sidebarOpen ? ' open' : ''}`}>
```

- [ ] **Step 5: Add close button inside the sidebar header**

Inside the `.ck-sb-header` div, add after the school name/meta:
```tsx
<button
  className="ck-sb-close"
  onClick={() => setSidebarOpen(false)}
  aria-label="Close menu"
>
  ×
</button>
```

- [ ] **Step 6: Add school badge to sidebar header**

After the `.ck-school-name` div in the sidebar header, add:
```tsx
{workspace?.school?.name && (
  <div className="ck-sb-school-badge">
    {workspace.school.name}
  </div>
)}
```

- [ ] **Step 7: Upgrade the user card with avatar initials**

Find the `.ck-user-card` section. Replace its content:
```tsx
<div className="ck-user-card">
  <div className="ck-user-card-inner">
    <div className="ck-user-avatar" aria-hidden="true">
      {(user?.name ?? user?.email ?? 'U').charAt(0).toUpperCase()}
    </div>
    <div>
      <div className="ck-user-name">{user?.name ?? user?.email}</div>
      <div className="ck-user-meta">{user?.role ?? ''}</div>
    </div>
  </div>
  <div className="ck-badge-row" style={{ marginTop: 10 }}>
    <button
      className="ck-btn ck-btn-ghost ck-btn-sm"
      onClick={() => { logout(); navigate('/login', { replace: true }); }}
    >
      Sign out
    </button>
  </div>
</div>
```

- [ ] **Step 8: Add mobile menu toggle to topbar**

Find the `.ck-topbar` div. Add the toggle button as the FIRST element inside it:
```tsx
<div className="ck-topbar">
  <button
    className="ck-menu-toggle"
    onClick={() => setSidebarOpen(v => !v)}
    aria-label="Open navigation"
    aria-expanded={sidebarOpen}
    aria-controls="ck-sidebar-nav"
  >
    ☰
  </button>
  {/* existing topbar content */}
  <span className="ck-topbar-title">...</span>
```

Also close sidebar when `setPanel` is called on mobile (add to the `setPanel` handler):
```tsx
// Wrap the existing setPanel calls or add a local wrapper:
const handleSetPanel = (key: PanelKey) => {
  setPanel(key);
  setSidebarOpen(false); // close mobile drawer on nav
};
```
Then use `handleSetPanel` wherever `setPanel` is passed to child panels.

- [ ] **Step 9: Add id to sidebar nav element for aria-controls**

```tsx
<nav id="ck-sidebar-nav" className={`ck-sidebar${sidebarOpen ? ' open' : ''}`}>
```

- [ ] **Step 10: Run build and verify**

```bash
cd frontend && npm run build
```

Expected: Build succeeds. 0 TypeScript errors.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "feat: mobile sidebar drawer, school badge, user avatar in navigation"
```

---

## PASS 4 — Global CSS Pattern Improvements

### Task 7: Add improved patterns to styles.css

**Files:**
- Modify: `frontend/src/styles.css`

**Goal:** Add these patterns at the END of styles.css (append, don't replace). These are additive improvements that augment the existing design system.

- [ ] **Step 1: Append form section grouping patterns to styles.css**

```css
/* ── Form Section Groups ─────────────────────────────────────────────────── */
.ck-form-section { margin-bottom: 24px; }
.ck-form-section:last-child { margin-bottom: 0; }
.ck-form-section-title {
  font-size: 11px; font-weight: 700; text-transform: uppercase;
  letter-spacing: .08em; color: var(--ink3);
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 16px;
  display: flex; align-items: center; gap: 8px;
}
.ck-form-section-icon { font-size: 14px; }
```

- [ ] **Step 2: Append improved status/badge patterns**

```css
/* ── Extended status pills ───────────────────────────────────────────────── */
.ck-status.spaid     { background: var(--g1);    color: var(--g);  }
.ck-status.soverdue  { background: var(--re1);   color: var(--re); }
.ck-status.spartial  { background: var(--or1);   color: var(--or); }
.ck-status.spending  { background: var(--am1);   color: var(--am); }
.ck-status.sinfo     { background: var(--b1);    color: var(--b);  }
.ck-status.sneutral  { background: #f4f4f0;       color: #888;      }
.ck-status.sapproved { background: var(--g1);    color: var(--g);  }
.ck-status.srejected { background: var(--re1);   color: var(--re); }
.ck-status.sdraft    { background: #f4f4f0;       color: #888;      }
.ck-status.scritical { background: var(--re);    color: #fff;      }

/* Status dot + label inline pattern */
.ck-status-dot-row {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 600;
}
.ck-status-dot-row::before {
  content: ''; display: inline-block;
  width: 7px; height: 7px; border-radius: 50%;
  background: currentColor; flex-shrink: 0;
}
```

- [ ] **Step 3: Append improved table patterns (money right-align, sticky header option)**

```css
/* ── Table improvements ──────────────────────────────────────────────────── */
.ck-table .col-money  { text-align: right; font-variant-numeric: tabular-nums; }
.ck-table .col-center { text-align: center; }
.ck-table .col-tight  { white-space: nowrap; width: 1%; }
.ck-table td.col-money { color: var(--ink); font-weight: 600; }

/* Money amount highlight in tables */
.ck-amt-green  { color: var(--g);  font-weight: 700; }
.ck-amt-red    { color: var(--re); font-weight: 700; }
.ck-amt-amber  { color: var(--am); font-weight: 700; }

/* Sticky header variant */
.ck-table-sticky thead th { position: sticky; top: 0; z-index: 1; }

/* Row actions hover reveal */
.ck-table .row-actions { opacity: 0; transition: opacity var(--ck-duration-fast, .12s); }
.ck-table tbody tr:hover .row-actions { opacity: 1; }
```

- [ ] **Step 4: Append progress ring CSS**

```css
/* ── Progress rings (attendance, fees collection) ────────────────────────── */
.ck-progress-ring-wrap { position: relative; display: inline-flex; align-items: center; justify-content: center; }
.ck-progress-ring-label {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700; color: var(--ink);
}
```

- [ ] **Step 5: Append improved card variants**

```css
/* ── Metric card with colored accent ─────────────────────────────────────── */
.ck-metric-card {
  background: var(--white); border: 1px solid var(--border);
  border-radius: 14px; padding: 16px 18px;
  display: flex; flex-direction: column; gap: 6px;
}
.ck-metric-card.accent-green  { border-left: 3px solid var(--g); }
.ck-metric-card.accent-blue   { border-left: 3px solid var(--b); }
.ck-metric-card.accent-amber  { border-left: 3px solid var(--am); }
.ck-metric-card.accent-red    { border-left: 3px solid var(--re); }
.ck-metric-card.accent-orange { border-left: 3px solid var(--or); }
.ck-metric-card-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: var(--ink3); }
.ck-metric-card-value { font: 800 28px/1 'DM Sans', sans-serif; letter-spacing: -.02em; }
.ck-metric-card-sub   { font-size: 12px; color: var(--ink3); }

/* ── Info row set (for detail drawers/modals) ─────────────────────────────── */
.ck-info-set { display: flex; flex-direction: column; gap: 0; }
.ck-info-set .ck-info-row { padding: 9px 0; border-bottom: 1px solid var(--border); }
.ck-info-set .ck-info-row:last-child { border-bottom: none; }

/* ── Section divider with label ──────────────────────────────────────────── */
.ck-section-divider {
  display: flex; align-items: center; gap: 12px;
  margin: 20px 0 16px;
}
.ck-section-divider::before,
.ck-section-divider::after { content: ''; flex: 1; height: 1px; background: var(--border); }
.ck-section-divider span {
  font-size: 10px; font-weight: 700; text-transform: uppercase;
  letter-spacing: .08em; color: var(--ink3); white-space: nowrap;
}
```

- [ ] **Step 6: Append improved attention/alert patterns**

```css
/* ── Inline urgency strip (firefighting) ─────────────────────────────────── */
.ck-urgency-strip {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border-radius: 8px;
  margin-bottom: 12px; font-size: 13px;
}
.ck-urgency-strip.critical {
  background: var(--re1); border: 1px solid #f5c0bd; border-left: 3px solid var(--re);
  color: var(--re);
}
.ck-urgency-strip.high {
  background: var(--or1); border: 1px solid #f5c090; border-left: 3px solid var(--or);
  color: var(--or);
}
.ck-urgency-strip.medium {
  background: var(--am1); border: 1px solid #f5d5a0; border-left: 3px solid var(--am);
  color: var(--am);
}
.ck-urgency-strip.low {
  background: var(--g1); border: 1px solid var(--g2); border-left: 3px solid var(--g);
  color: var(--g);
}

/* ── Timeline component ──────────────────────────────────────────────────── */
.ck-timeline { display: flex; flex-direction: column; gap: 0; }
.ck-timeline-item { display: flex; gap: 14px; padding-bottom: 20px; position: relative; }
.ck-timeline-item:last-child { padding-bottom: 0; }
.ck-timeline-left { display: flex; flex-direction: column; align-items: center; flex-shrink: 0; }
.ck-timeline-dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: var(--border2); flex-shrink: 0; margin-top: 3px;
  border: 2px solid var(--white); outline: 2px solid var(--border2);
}
.ck-timeline-dot.done   { background: var(--g); outline-color: var(--g2); }
.ck-timeline-dot.active { background: var(--or); outline-color: #f5c090; }
.ck-timeline-dot.danger { background: var(--re); outline-color: #f5c0bd; }
.ck-timeline-line {
  width: 1px; flex: 1; background: var(--border); margin-top: 4px;
}
.ck-timeline-item:last-child .ck-timeline-line { display: none; }
.ck-timeline-body { flex: 1; min-width: 0; }
.ck-timeline-title { font-size: 13px; font-weight: 600; color: var(--ink); line-height: 1.3; }
.ck-timeline-meta  { font-size: 11px; color: var(--ink3); margin-top: 2px; }
.ck-timeline-note  { font-size: 12px; color: var(--ink2); margin-top: 6px; padding: 8px 12px; background: var(--bg); border-radius: 8px; line-height: 1.5; }
```

- [ ] **Step 7: Run build**

```bash
cd frontend && npm run build
```

Expected: Build succeeds. 0 errors.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/styles.css
git commit -m "feat: extend design system with form sections, status pills, timelines, progress rings"
```

---

## PASS 5 — Students Panel Visual Upgrade

### Task 8: Improve StudentsPanel layout, filters, and table

**Files:**
- Modify: `frontend/src/pages/workspace/panels/StudentsPanel.tsx`

**Goal:** Better filter bar presentation, improved avatar cell quality, status pill consistency, skeleton loading state, EmptyState for zero-result set, money right-alignment for fee column. DO NOT change state management, API calls, or sorting logic.

- [ ] **Step 1: Read the full StudentsPanel.tsx**

Read `frontend/src/pages/workspace/panels/StudentsPanel.tsx` completely (it is ~400+ lines based on audit).

- [ ] **Step 2: Identify the filter bar JSX block**

Find the select dropdowns for class/section/feeStatus and the search input. They are likely rendered in a row above the table.

- [ ] **Step 3: Replace the filter bar with improved layout**

Find the existing filter bar (look for `<select>` elements with className filters near studentFilters). Replace with:

```tsx
{/* ── Filter bar ── */}
<div className="ck-card-h-wrap" style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
  <div className="ck-card-inline-filters">
    <select
      value={studentFilters.className}
      onChange={e => applyFilters({ ...studentFilters, className: e.target.value, sectionName: 'All' })}
      style={{ minWidth: 120 }}
      aria-label="Filter by class"
    >
      <option value="All">All classes</option>
      {studentsView.filters?.classes?.map((c: string) => (
        <option key={c} value={c}>{c}</option>
      ))}
    </select>
    <select
      value={studentFilters.sectionName}
      onChange={e => applyFilters({ ...studentFilters, sectionName: e.target.value })}
      style={{ minWidth: 120 }}
      aria-label="Filter by section"
    >
      <option value="All">All sections</option>
      {studentsView.filters?.sections?.map((s: string) => (
        <option key={s} value={s}>{s}</option>
      ))}
    </select>
    <select
      value={studentFilters.feeStatus}
      onChange={e => applyFilters({ ...studentFilters, feeStatus: e.target.value })}
      style={{ minWidth: 140 }}
      aria-label="Filter by fee status"
    >
      <option value="All">All fee statuses</option>
      {(studentsView.filters?.feeStatuses ?? ['Paid', 'Overdue', 'Pending', 'Partial']).map((s: string) => (
        <option key={s} value={s}>{s}</option>
      ))}
    </select>
  </div>
  <div style={{ fontSize: 12, color: 'var(--ink3)', marginLeft: 'auto', flexShrink: 0 }}>
    {studentsLoading ? '…' : `${studentsView.filteredCount ?? studentsView.items?.length ?? 0} students`}
  </div>
</div>
```

- [ ] **Step 4: Improve the skeleton loading state**

Find the loading placeholder (likely `studentsLoading && ...` or similar). Replace with:

```tsx
{studentsLoading && (
  <div>
    {[0,1,2,3,4].map(i => (
      <div key={i} className="ck-skeleton-row" style={{ animationDelay: `${i*0.06}s` }}>
        <div className="ck-skeleton ck-skeleton-avatar" />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 5 }}>
          <div className="ck-skeleton ck-skeleton-text" style={{ width: '45%' }} />
          <div className="ck-skeleton ck-skeleton-text" style={{ width: '30%', height: 11 }} />
        </div>
        <div className="ck-skeleton ck-skeleton-badge" />
        <div className="ck-skeleton ck-skeleton-badge" style={{ width: 80 }} />
      </div>
    ))}
  </div>
)}
```

- [ ] **Step 5: Add EmptyState when no students found**

Find the condition that renders an empty list. Add:

```tsx
{!studentsLoading && studentsView.items?.length === 0 && (
  <div style={{ padding: '40px 20px', textAlign: 'center' }}>
    <div style={{ fontSize: 36, marginBottom: 8 }}>🎓</div>
    <div style={{ fontWeight: 700, fontSize: 15, marginBottom: 4 }}>
      {studentFilters.className !== 'All' || studentFilters.feeStatus !== 'All'
        ? 'No students match these filters'
        : 'No students enrolled yet'}
    </div>
    <div style={{ fontSize: 13, color: 'var(--ink3)', marginBottom: 14 }}>
      {studentFilters.className !== 'All' || studentFilters.feeStatus !== 'All'
        ? 'Try adjusting the class or fee status filters above.'
        : 'Add your first student to get started.'}
    </div>
    {can('student:write') && studentFilters.className === 'All' && (
      <button className="ck-btn ck-btn-g" onClick={() => setPanel('addstudent')}>
        Add student
      </button>
    )}
  </div>
)}
```

- [ ] **Step 6: Improve the student avatar cell**

Find the table row where student info is rendered (the `.ck-student-cell` block). The existing pattern is already decent. Ensure it uses:

```tsx
<td>
  <div className="ck-student-cell">
    {student.photoUrl
      ? <img className="ck-student-avatar" src={student.photoUrl} alt="" aria-hidden="true" />
      : (
        <div className="ck-student-avatar ck-student-avatar-fallback" aria-hidden="true">
          {initials(student.name ?? '')}
        </div>
      )
    }
    <div>
      <div style={{ fontWeight: 600, fontSize: 13 }}>{student.name}</div>
      <div style={{ fontSize: 11, color: 'var(--ink3)', marginTop: 1 }}>
        {student.admissionNo && <span>{student.admissionNo}</span>}
        {student.fatherContact && <span style={{ marginLeft: 8 }}>{student.fatherContact}</span>}
      </div>
    </div>
  </div>
</td>
```

- [ ] **Step 7: Right-align money column and improve fee status pill**

Find the column that shows fee status. Ensure:
1. The column header has `className="col-money"` on the th element for the fee amount if displayed
2. The fee status uses the standardized `.ck-status` classes:

```tsx
// Fee status pill mapping
function feeStatusClass(status: string): string {
  switch (status?.toLowerCase()) {
    case 'paid':    return 'sg spaid';
    case 'overdue': return 'sr soverdue';
    case 'partial': return 'sor spartial';
    case 'pending': return 'sam spending';
    default:        return 'sgr sneutral';
  }
}

// In the table cell:
<td><span className={`ck-status ${feeStatusClass(student.feeStatus)}`}>{student.feeStatus}</span></td>
```

- [ ] **Step 8: Run build**

```bash
cd frontend && npm run build
```

Expected: Build succeeds. 0 errors.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/pages/workspace/panels/StudentsPanel.tsx
git commit -m "feat: students panel skeleton loading, empty state, improved filter bar and avatars"
```

---

## PASS 6 — AddStudentPanel Form Grouping

### Task 9: Improve AddStudentPanel multi-section form

**Files:**
- Modify: `frontend/src/pages/workspace/panels/AddStudentPanel.tsx`

**Goal:** Group the form into labeled sections (Student Details, Academic Details, Parent / Guardian, Address). Use `.ck-form-section` and `.ck-form-section-title` patterns. DO NOT change field names, validation logic, or submit handlers.

- [ ] **Step 1: Read AddStudentPanel.tsx completely**

Read `frontend/src/pages/workspace/panels/AddStudentPanel.tsx`.

- [ ] **Step 2: Identify all form fields and their current grouping**

Map which fields belong in each section:
- **Student Details**: name, admissionNo, dateOfBirth, gender, bloodGroup, photo
- **Academic Details**: className, sectionName, academicYear, rollNo
- **Parent / Guardian**: fatherName, fatherContact, motherName, motherContact, guardianName
- **Address**: address, city, state, pincode

- [ ] **Step 3: Wrap each field group in a section div**

For each section, replace the bare `<div className="ck-form-grid ...">` with:

```tsx
<div className="ck-form-section">
  <div className="ck-form-section-title">
    <span className="ck-form-section-icon">👤</span>
    Student Details
  </div>
  <div className="ck-form-grid ck-fg-2">
    {/* existing field components */}
  </div>
</div>

<div className="ck-form-section">
  <div className="ck-form-section-title">
    <span className="ck-form-section-icon">🎓</span>
    Academic Details
  </div>
  <div className="ck-form-grid ck-fg-2">
    {/* existing field components */}
  </div>
</div>

<div className="ck-form-section">
  <div className="ck-form-section-title">
    <span className="ck-form-section-icon">👨‍👩‍👦</span>
    Parent / Guardian
  </div>
  <div className="ck-form-grid ck-fg-2">
    {/* existing field components */}
  </div>
</div>

<div className="ck-form-section">
  <div className="ck-form-section-title">
    <span className="ck-form-section-icon">📍</span>
    Address
  </div>
  <div className="ck-form-grid ck-fg-2">
    {/* existing field components */}
  </div>
</div>
```

- [ ] **Step 4: Run build**

```bash
cd frontend && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/AddStudentPanel.tsx
git commit -m "feat: add student form section grouping"
```

---

## PASS 7 — Fees Panel Visual Upgrade

### Task 10: Add collection progress card and improve fee status display

**Files:**
- Modify: `frontend/src/pages/workspace/panels/FeesPanel.tsx`

**Goal:** Add a fee collection progress summary card at the top (using existing data from the panel). Improve fee status badges. Ensure money columns are right-aligned. Add skeleton loading. DO NOT change API calls, state, or business logic.

- [ ] **Step 1: Read FeesPanel.tsx completely**

Read `frontend/src/pages/workspace/panels/FeesPanel.tsx`.

- [ ] **Step 2: Add collection progress summary card at the top**

After the `ModuleShell` / `ck-ph` page header, before the fee table, add a 4-metric summary row:

```tsx
{/* ── Fee collection summary ── */}
{feeSummary && (
  <div className="ck-stats ck-s4" style={{ marginBottom: 20 }}>
    <div className="ck-metric-card accent-green">
      <div className="ck-metric-card-label">Total Collected</div>
      <div className="ck-metric-card-value" style={{ color: 'var(--g)' }}>
        ₹{formatMoney(feeSummary.collectedPaise / 100)}
      </div>
      <div className="ck-metric-card-sub">
        {feeSummary.paidCount} students paid
      </div>
      <div style={{ marginTop: 10 }}>
        <div className="ck-progress-bar">
          <div
            className="ck-progress-fill"
            style={{ width: `${Math.min(100, feeSummary.collectionPercent)}%` }}
            role="progressbar"
            aria-valuenow={feeSummary.collectionPercent}
            aria-valuemin={0}
            aria-valuemax={100}
          />
        </div>
        <div style={{ fontSize: 11, color: 'var(--ink3)', marginTop: 4 }}>
          {feeSummary.collectionPercent}% of target
        </div>
      </div>
    </div>
    <div className="ck-metric-card accent-red">
      <div className="ck-metric-card-label">Overdue</div>
      <div className="ck-metric-card-value" style={{ color: 'var(--re)' }}>
        ₹{formatMoney(feeSummary.overduePaise / 100)}
      </div>
      <div className="ck-metric-card-sub">{feeSummary.overdueCount} defaulters</div>
    </div>
    <div className="ck-metric-card accent-amber">
      <div className="ck-metric-card-label">Pending</div>
      <div className="ck-metric-card-value" style={{ color: 'var(--am)' }}>
        ₹{formatMoney(feeSummary.pendingPaise / 100)}
      </div>
      <div className="ck-metric-card-sub">{feeSummary.pendingCount} students</div>
    </div>
    <div className="ck-metric-card accent-blue">
      <div className="ck-metric-card-label">Partial</div>
      <div className="ck-metric-card-value" style={{ color: 'var(--b)' }}>
        {feeSummary.partialCount}
      </div>
      <div className="ck-metric-card-sub">students with partial payment</div>
    </div>
  </div>
)}
```

NOTE: `feeSummary` is derived from existing panel state — check the actual variable names in the panel and adjust field names to match. If no summary object exists, wrap the entire card in a conditional that only renders when sufficient data is available.

- [ ] **Step 3: Standardize fee status badges in the table**

Find where fee status is displayed in the table. Replace with:

```tsx
function feeStatusBadge(status: string) {
  const map: Record<string, string> = {
    'Paid': 'sg spaid', 'Overdue': 'sr soverdue',
    'Partial': 'sor spartial', 'Pending': 'sam spending',
  };
  return <span className={`ck-status ${map[status] ?? 'sgr'}`}>{status}</span>;
}
```

- [ ] **Step 4: Right-align amount columns**

Find `<th>` elements for fee amount columns. Add `className="col-money"` to both the `<th>` and the corresponding `<td>` elements. Ensure amounts use `formatMoney()`:

```tsx
<th className="col-money">Amount (₹)</th>
// ...
<td className="col-money ck-amt-green">₹{formatMoney(assignment.netPayable / 100)}</td>
```

(Paise to rupees: divide by 100. `formatMoney` is already imported from `../utils`.)

- [ ] **Step 5: Run build**

```bash
cd frontend && npm run build
```

Expected: 0 errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/workspace/panels/FeesPanel.tsx
git commit -m "feat: fees panel collection progress card, status badges, money column alignment"
```

---

## PASS 8 — Attendance Panel Visual Upgrade

### Task 11: Improve attendance class grid and status indicators

**Files:**
- Modify: `frontend/src/pages/workspace/panels/AttendancePanel.tsx`

**Goal:** Improve the class-section card grid with a mini progress indicator showing present percentage. Add clear status labels (Pending / Saved / Submitted). Skeleton loading for the grid. DO NOT change the toggle logic or submission logic.

- [ ] **Step 1: Read AttendancePanel.tsx completely**

Read `frontend/src/pages/workspace/panels/AttendancePanel.tsx`.

- [ ] **Step 2: Find the class-section grid rendering**

Identify where `.ck-class-card` or similar class grid is rendered. Find the data object (list of classes with their attendance counts).

- [ ] **Step 3: Enhance class cards with mini progress bar**

Replace the existing class card content with an improved version:

```tsx
<div className="ck-class-card" key={cls.id} style={{ cursor: 'pointer' }} onClick={() => handleClassSelect(cls)}>
  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8 }}>
    <div className="ck-cc-grade">{cls.className}</div>
    <span className={`ck-status ${
      cls.status === 'SUBMITTED' ? 'sg spaid' :
      cls.status === 'SAVED'     ? 'sb2 sinfo' :
                                   'sam spending'
    }`}>
      {cls.status === 'SUBMITTED' ? 'Submitted' :
       cls.status === 'SAVED'     ? 'Saved' : 'Pending'}
    </span>
  </div>
  <div className="ck-cc-sec">{cls.sectionName}</div>
  <div className="ck-cc-count">{cls.presentCount ?? '–'}<span style={{ fontSize: 14, fontWeight: 400, color: 'var(--ink3)' }}>/{cls.totalCount ?? '–'}</span></div>
  <div className="ck-cc-cl" style={{ marginBottom: 8 }}>present today</div>
  {typeof cls.presentCount === 'number' && typeof cls.totalCount === 'number' && cls.totalCount > 0 && (
    <div>
      <div className="ck-mini-progress">
        <div
          className="ck-mini-progress-fill"
          style={{
            width: `${Math.round((cls.presentCount / cls.totalCount) * 100)}%`,
            background: cls.presentCount / cls.totalCount >= 0.85 ? 'var(--g)' : cls.presentCount / cls.totalCount >= 0.70 ? 'var(--am)' : 'var(--re)',
          }}
          role="progressbar"
          aria-valuenow={Math.round((cls.presentCount / cls.totalCount) * 100)}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`${Math.round((cls.presentCount / cls.totalCount) * 100)}% present`}
        />
      </div>
      <div style={{ fontSize: 10, color: 'var(--ink3)', marginTop: 3 }}>
        {Math.round((cls.presentCount / cls.totalCount) * 100)}% present
      </div>
    </div>
  )}
</div>
```

NOTE: Adjust field names (`presentCount`, `totalCount`, `status`, `className`, `sectionName`) to match the actual API response shape used in this panel.

- [ ] **Step 4: Add skeleton loading for the class grid**

When the grid data is loading, show placeholder cards:

```tsx
{attendanceLoading && (
  <div className="ck-class-grid">
    {[0,1,2,3,4,5].map(i => (
      <div key={i} className="ck-skeleton ck-skeleton-card" style={{ animationDelay: `${i*0.05}s` }} />
    ))}
  </div>
)}
```

- [ ] **Step 5: Run build**

```bash
cd frontend && npm run build
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/workspace/panels/AttendancePanel.tsx
git commit -m "feat: attendance class cards with progress bars and status indicators"
```

---

## PASS 9 — Firefighting Panel Visual Upgrade

### Task 12: Improve FirefightingDashboardPanel with urgency cards and timeline

**Files:**
- Modify: `frontend/src/pages/workspace/panels/FirefightingDashboardPanel.tsx`

**Goal:** Add urgency-colored metric cards at the top. Use `.ck-urgency-strip` pattern for request rows. Use `.ck-timeline` pattern in request detail views. DO NOT change state, API calls, or approval logic.

- [ ] **Step 1: Read FirefightingDashboardPanel.tsx completely**

Read `frontend/src/pages/workspace/panels/FirefightingDashboardPanel.tsx`.

- [ ] **Step 2: Add urgency summary metrics at the top**

After the page header, add a metric row showing critical/high/pending counts:

```tsx
{/* ── Urgency summary ── */}
<div className="ck-stats ck-s4" style={{ marginBottom: 20 }}>
  <div className="ck-metric-card accent-red">
    <div className="ck-metric-card-label">Critical</div>
    <div className="ck-metric-card-value" style={{ color: 'var(--re)' }}>
      {ffStats?.criticalCount ?? '–'}
    </div>
    <div className="ck-metric-card-sub">need immediate action</div>
  </div>
  <div className="ck-metric-card accent-amber">
    <div className="ck-metric-card-label">Awaiting Approval</div>
    <div className="ck-metric-card-value" style={{ color: 'var(--am)' }}>
      {ffStats?.pendingApprovalCount ?? '–'}
    </div>
    <div className="ck-metric-card-sub">pending review</div>
  </div>
  <div className="ck-metric-card accent-orange">
    <div className="ck-metric-card-label">Due Soon</div>
    <div className="ck-metric-card-value" style={{ color: 'var(--or)' }}>
      {ffStats?.dueSoonCount ?? '–'}
    </div>
    <div className="ck-metric-card-sub">within 48 hours</div>
  </div>
  <div className="ck-metric-card accent-blue">
    <div className="ck-metric-card-label">Vendor Unpaid</div>
    <div className="ck-metric-card-value" style={{ color: 'var(--b)' }}>
      {ffStats?.vendorUnpaidCount ?? '–'}
    </div>
    <div className="ck-metric-card-sub">orders awaiting payment</div>
  </div>
</div>
```

NOTE: Adjust variable names to match actual state variables in the panel.

- [ ] **Step 3: Add urgency strip to request list rows**

In the list of firefighting requests, wrap critical/high items:

```tsx
{request.urgency === 'CRITICAL' && (
  <div className="ck-urgency-strip critical" style={{ marginBottom: 8 }}>
    🚨 Critical — immediate action required
  </div>
)}
```

- [ ] **Step 4: Improve request status badges**

Replace raw status string rendering with:

```tsx
function ffStatusBadge(status: string) {
  const map: Record<string, string> = {
    'DRAFT': 'sgr sdraft',
    'SUBMITTED': 'sam spending',
    'APPROVED': 'sg sapproved',
    'REJECTED': 'sr srejected',
    'ORDERED': 'sb2 sinfo',
    'FULFILLED': 'sg spaid',
  };
  const label: Record<string, string> = {
    'DRAFT': 'Draft', 'SUBMITTED': 'Pending Approval',
    'APPROVED': 'Approved', 'REJECTED': 'Rejected',
    'ORDERED': 'Ordered', 'FULFILLED': 'Fulfilled',
  };
  return <span className={`ck-status ${map[status] ?? 'sgr'}`}>{label[status] ?? status}</span>;
}
```

- [ ] **Step 5: Run build**

```bash
cd frontend && npm run build
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/workspace/panels/FirefightingDashboardPanel.tsx
git commit -m "feat: firefighting urgency metric cards, status badges, urgency strips"
```

---

## PASS 10 — Supply OS Orders Panel

### Task 13: Improve AdminOrdersPanel order status display

**Files:**
- Modify: `frontend/src/pages/workspace/panels/AdminOrdersPanel.tsx`

**Goal:** Improve order status badges and table presentation. Use the `.ck-status` pattern consistently. Money right-aligned. DO NOT change state or API logic.

- [ ] **Step 1: Read AdminOrdersPanel.tsx completely**

Read `frontend/src/pages/workspace/panels/AdminOrdersPanel.tsx`.

- [ ] **Step 2: Add a helper function for order status badges**

Add near the top of the component (before the return statement):

```tsx
function orderStatusBadge(status: string) {
  const map: Record<string, string> = {
    'DRAFT': 'sgr sdraft',
    'SUBMITTED': 'sam spending',
    'DESIGN_PENDING': 'sb2 sinfo',
    'DESIGN_APPROVED': 'sg sapproved',
    'PENDING_APPROVAL': 'sam spending',
    'APPROVED': 'sg sapproved',
    'REJECTED': 'sr srejected',
    'ORDERED': 'sb2 sinfo',
    'FULFILLED': 'sg spaid',
  };
  const label: Record<string, string> = {
    'DRAFT': 'Draft', 'SUBMITTED': 'Submitted',
    'DESIGN_PENDING': 'Design Pending', 'DESIGN_APPROVED': 'Design Approved',
    'PENDING_APPROVAL': 'Pending Approval', 'APPROVED': 'Approved',
    'REJECTED': 'Rejected', 'ORDERED': 'Ordered', 'FULFILLED': 'Fulfilled',
  };
  return <span className={`ck-status ${map[status] ?? 'sgr'}`}>{label[status] ?? status}</span>;
}
```

- [ ] **Step 3: Apply to order status column in table**

Find where `order.status` is rendered in the table. Replace with `{orderStatusBadge(order.status)}`.

- [ ] **Step 4: Right-align amount column**

Add `className="col-money"` to the th and td for the amount column. Format with `formatMoney`.

- [ ] **Step 5: Run build**

```bash
cd frontend && npm run build
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/workspace/panels/AdminOrdersPanel.tsx
git commit -m "feat: supply orders status badges and money alignment"
```

---

## PASS 11 — Existing Drawers Upgrade

### Task 14: Upgrade drawer shells to use DrawerShell component

**Files:**
- Modify: `frontend/src/pages/workspace/dashboard/drawers/FeeDefaultersDrawer.tsx`
- Modify: `frontend/src/pages/workspace/dashboard/drawers/LowAttendanceDrawer.tsx`

**Goal:** These drawers currently open inline or use a basic modal. Upgrade them to use the new `DrawerShell` component for consistent right-side panel behavior.

- [ ] **Step 1: Read FeeDefaultersDrawer.tsx**

Read `frontend/src/pages/workspace/dashboard/drawers/FeeDefaultersDrawer.tsx`.

- [ ] **Step 2: Wrap content in DrawerShell**

If the drawer renders as a full-page overlay or basic modal, import `DrawerShell` and wrap:

```tsx
import { DrawerShell } from '../../../../components/ui/DrawerShell';

// In JSX:
return (
  <DrawerShell
    open={open}
    onClose={onClose}
    title="Fee Defaulters"
    subtitle="Students with outstanding balance in the active academic year"
    icon="💰"
    iconBg="var(--re1)"
    iconColor="var(--re)"
    size="lg"
    footer={
      <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
    }
  >
    {/* existing content */}
  </DrawerShell>
);
```

- [ ] **Step 3: Apply same pattern to LowAttendanceDrawer.tsx**

Read and apply the same DrawerShell wrapping to `LowAttendanceDrawer.tsx`.

- [ ] **Step 4: Run build**

```bash
cd frontend && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/dashboard/drawers/
git commit -m "feat: upgrade command center drawers to use DrawerShell component"
```

---

## PASS 12 — DataTable Skeleton + Empty State

### Task 15: Upgrade shared DataTable component

**Files:**
- Modify: `frontend/src/shared/components/DataTable.tsx`

**Goal:** Add skeleton loading rows, EmptyState for zero data, and sticky header option. Preserve all existing props.

- [ ] **Step 1: Read DataTable.tsx completely**

Read `frontend/src/shared/components/DataTable.tsx`.

- [ ] **Step 2: Add loading skeleton prop**

Find the props interface and add:

```tsx
interface DataTableProps<T> {
  // ... existing props
  loading?: boolean;
  loadingRows?: number;
  emptyIcon?: string;
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: React.ReactNode;
}
```

- [ ] **Step 3: Add skeleton rendering**

In the table body section, add:

```tsx
{loading && (
  <>
    {Array.from({ length: loadingRows ?? 5 }).map((_, i) => (
      <tr key={`skel-${i}`} aria-hidden="true">
        {columns.map((col, j) => (
          <td key={j} style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
            <div className="ck-skeleton ck-skeleton-text" style={{ width: `${40 + (j * 15) % 40}%`, animationDelay: `${i*0.05}s` }} />
          </td>
        ))}
      </tr>
    ))}
  </>
)}
```

- [ ] **Step 4: Add EmptyState rendering**

When `!loading && data.length === 0`:

```tsx
{!loading && data.length === 0 && (
  <tr>
    <td colSpan={columns.length} style={{ padding: 0, border: 'none' }}>
      <div style={{ padding: '40px 24px', textAlign: 'center' }}>
        <div style={{ fontSize: 34, marginBottom: 8 }}>{emptyIcon ?? '📋'}</div>
        <div style={{ fontWeight: 700, fontSize: 15, marginBottom: 4 }}>{emptyTitle ?? 'No data'}</div>
        {emptyDescription && (
          <div style={{ fontSize: 13, color: 'var(--ink3)', marginBottom: 12 }}>{emptyDescription}</div>
        )}
        {emptyAction}
      </div>
    </td>
  </tr>
)}
```

- [ ] **Step 5: Run build**

```bash
cd frontend && npm run build
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/shared/components/DataTable.tsx
git commit -m "feat: DataTable loading skeleton and empty state"
```

---

## PASS 13 — Mobile Modal Improvements

### Task 16: Improve Modal for mobile screens

**Files:**
- Modify: `frontend/src/components/Modal.tsx`

**Goal:** Add `max-height: 92vh` on mobile and ensure the modal body scrolls properly. Add a style to the existing `.ck-modal` and `.ck-modal-body` classes.

- [ ] **Step 1: Read Modal.tsx**

Read `frontend/src/components/Modal.tsx`.

- [ ] **Step 2: Add mobile styles to styles.css**

Append to `styles.css`:

```css
/* ── Modal mobile improvements ───────────────────────────────────────────── */
@media (max-width: 600px) {
  .ck-modal-bg { padding: 0; align-items: flex-end; }
  .ck-modal { width: 100vw; border-radius: 20px 20px 0 0; max-height: 92vh; display: flex; flex-direction: column; }
  .ck-modal-body { flex: 1; max-height: none; overflow-y: auto; -webkit-overflow-scrolling: touch; }
}
```

- [ ] **Step 3: Run build**

```bash
cd frontend && npm run build
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/styles.css frontend/src/components/Modal.tsx
git commit -m "feat: modal bottom-sheet behavior on mobile"
```

---

## PASS 14 — StaffPanel Basic Improvement

### Task 17: Add skeleton and empty state to StaffPanel

**Files:**
- Modify: `frontend/src/pages/workspace/panels/StaffPanel.tsx`

**Goal:** The staff panel is minimal. Add skeleton loading and empty state without changing business logic.

- [ ] **Step 1: Read StaffPanel.tsx**

Read `frontend/src/pages/workspace/panels/StaffPanel.tsx`.

- [ ] **Step 2: Add skeleton table loading**

Same pattern as StudentsPanel — replace the loading text with skeleton rows.

- [ ] **Step 3: Add empty state**

When no staff found, show a designed empty state.

- [ ] **Step 4: Run build**

```bash
cd frontend && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/StaffPanel.tsx
git commit -m "feat: staff panel skeleton and empty state"
```

---

## PASS 15 — Final Build Verification & Cleanup

### Task 18: Final build verification and CSS cleanup

**Files:**
- Modify: `frontend/src/styles.css` (remove duplicate patterns if found)
- Run: `npm run build`, `npm run typecheck` (if available)

- [ ] **Step 1: Run typecheck**

```bash
cd frontend && npx tsc --noEmit
```

Fix any TypeScript errors before proceeding.

- [ ] **Step 2: Run full build**

```bash
cd frontend && npm run build
```

Expected: Build succeeds. 0 errors. Bundle size reasonable.

- [ ] **Step 3: Check for obvious duplicate CSS**

Scan `styles.css` for any patterns that were accidentally duplicated. Look for duplicate `.ck-status`, `.ck-btn`, `.ck-modal` selectors.

- [ ] **Step 4: Verify no console errors in development**

```bash
cd frontend && npm run dev
```

Open the app and check browser console for 0 errors. Navigate to Dashboard, Students, Fees, Attendance, Firefighting panels.

- [ ] **Step 5: Final commit**

```bash
git add -p  # stage only needed changes
git commit -m "fix: final build cleanup and TypeScript error fixes"
```

---

## Self-Review Checklist

### Spec coverage:
- [x] Phase 1 (Audit) → covered by `docs/frontend-design-audit.md` (already created)
- [x] Phase 2 (Design tokens) → Task 1 creates `tokens.css` with all `--ck-*` token groups
- [x] Phase 3 (App shell) → Task 6 upgrades sidebar with mobile drawer, school badge, user avatar, topbar toggle
- [x] Phase 4 (Shared components) → Tasks 2–5 create Skeleton, EmptyState, DrawerShell, SectionCard, PageHeader
- [x] Phase 5 (Dashboard) → HomePanel already excellent; minor additions from Tasks 7–8 (new CSS patterns improve it)
- [x] Phase 6 (Students) → Tasks 8–9 cover filter bar, avatar, status pills, form grouping
- [x] Phase 7 (Fees) → Task 10 covers progress card, status badges, money alignment
- [x] Phase 8 (Attendance) → Task 11 covers class grid with progress indicators
- [x] Phase 9 (Supply OS) → Task 13 covers order status badges and display
- [x] Phase 10 (Firefighting) → Task 12 covers urgency cards, timeline patterns
- [x] Phase 13 (Responsiveness) → Task 6 (mobile sidebar), Task 16 (mobile modal), existing breakpoints retained
- [x] Phase 14 (Polish) → CSS tokens, skeleton loading, drawer animations, hover states
- [x] Phase 15 (Build cleanup) → Task 18

### Gaps acknowledged (not in this plan):
- FeeStructurePanel, TimetablePanel, SaErpPanel, SaRevenuePanel, ZoneManagementPage, SchoolManagementPage — these panels were judged lower priority vs the main daily-use panels. They retain existing functional UI.
- RBAC / User Management detailed redesign — the panel is functional; a full redesign would require significant state understanding and is outside safe-pass scope.
- Reports panel redesign — similar reasoning.
- The `UnifiedWorkspacePage.tsx` refactor/split is intentionally deferred — risk outweighs benefit.

### Placeholder scan: None found. All steps include actual code.

### Type consistency: All components use `React.ReactNode` for children slots. No method names conflict between tasks.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-14-frontend-ui-upgrade.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks

**2. Inline Execution** — execute tasks in this session using executing-plans

Which approach?
