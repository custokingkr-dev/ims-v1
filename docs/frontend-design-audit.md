# Custoking IMS — Frontend Design Audit

## Executive Summary

The existing frontend is a solid React + Vite + TypeScript SPA with a surprisingly good design foundation. The command center and annual planner in particular show high-quality design work. The main gaps are in consistency, mobile responsiveness, and a few underdesigned panels.

---

## Current UI Problems

### 1. Design Token Architecture
- CSS variables use cryptic shorthand names (`--bg`, `--g`, `--b`, `--am`, `--re`) that are not self-documenting and resist theming
- No spacing scale — spacing values are hardcoded (14px, 18px, 26px) inconsistently across components
- No z-index system — z-index values sprinkled as magic numbers (20, 60, 100)
- No animation duration tokens — durations scattered as literals
- `styles.css` is 864 lines in a single file — no separation of concerns

### 2. Navigation & Sidebar
- On mobile (< 820px) the sidebar becomes `position:static` which stacks vertically — bad UX for mobile users
- No mobile menu toggle/drawer — sidebar just falls above the content
- No visual collapse for laptop-size screens (1024–1280px)
- Active nav item highlighting uses a left-border that's subtle — could be more prominent
- No badge/count indicators on nav items (e.g. "3 pending approvals")

### 3. Page Layout & Headers
- `ModuleShell` renders inconsistently — some panels use it, some don't
- Page titles use `ck-ph-l h1` with Fraunces font at 26px — generally consistent but a few panels skip it
- No consistent breadcrumb/context line for sub-panels (e.g. Add Student, Fee Structure)
- Topbar only shows the panel title — doesn't show school name, user, or search on desktop

### 4. Panels with Thin Design
- **StudentsPanel**: table is functional but lacks avatar quality, no "quick action" area, filter bar is basic
- **FeesPanel**: no collection progress bars, currency formatting inconsistent, no strong paid/overdue visual treatment
- **AttendancePanel**: class-section grid cards are basic (no progress ring, no submission status)
- **StaffPanel**: minimal content, looks incomplete
- **FeeStructurePanel**: form-heavy, no section grouping
- **TimetablePanel**: basic table, not visually designed

### 5. Empty & Loading States
- Loading state: Just text "Loading…" in most panels — no skeleton
- Empty state: Some panels show nothing, some show basic text
- No skeleton loading for tables, cards, or stat sections
- Error states: Often just an inline text notice, no designed error card

### 6. Forms
- `AddStudentPanel`: Long single-column form without section grouping
- Fee forms: Mixed grid layouts, inconsistent label sizing
- No "section divider" pattern for grouped multi-section forms
- File upload (import zone) is functional but not polished

### 7. Status & Badges
- `.ck-status` at 11px is sometimes too small to scan at a glance
- Status labels are inconsistent: some use ALL_CAPS from API, some are prettified
- No consistent "dot + label" pattern for live status indicators outside the command center

### 8. Tables
- `ck-table` has hover states only, no row selection visual, no sticky header
- No column alignment — amounts (money) not right-aligned
- No "actions column" pattern — action buttons placed inconsistently
- Mobile: tables overflow horizontally without any fallback

### 9. Responsiveness
- Grid breakpoints exist (1100px, 820px) but some panels ignore them
- Form grids (`ck-fg-4`, `ck-fg-5`, `ck-fg-6`) collapse to 1-col on mobile — good, but happens all at once
- No tablet intermediate state (820px–1100px)
- Modal (`ck-modal`) has no mobile-specific height handling — max-height: 72vh can still cut off content

### 10. Code Quality
- `UnifiedWorkspacePage.tsx` is 600+ lines — handles all panel state, should be split eventually (but risky to move)
- `HomePanel.tsx` is 1300+ lines — acceptable given the complexity
- `styles.css` is 864 lines — should be organized into logical sections (already somewhat sectioned with comments)
- Some inline styles in JSX (should be CSS classes)
- Duplicate table styles: `.ck-table`, `.table`, `ap-plan-table` all define similar table patterns

---

## Design Opportunities

1. **Sidebar premium feel**: Add logo/brand wordmark, school selector, user avatar, and subtle gradient/texture
2. **Topbar upgrade**: Show school name, user avatar, search button, notification badge
3. **Stat cards with sparklines**: Already exists in HomePanel — extend to Fees and other module headers
4. **Fee collection progress card**: Visual progress ring or bar at top of Fees panel
5. **Attendance class grid**: Add present % per class card with mini progress ring
6. **Status timeline in Firefighting**: Timeline component for request lifecycle
7. **Student profile drawer**: Better organized with tabbed sections (Profile, Fees, Attendance)
8. **Skeleton loading**: Consistent shimmer pattern across all panels
9. **INR formatting**: Consistent ₹X,XX,XXX formatting using existing `formatMoney()` utility
10. **Print/export button styling**: Consistent action button row at page header level

---

## Proposed Design Direction

**Visual identity**: "Warm Enterprise" — the existing warm beige background (`#f7f6f3`) and green primary are excellent and distinctive. Keep these. Upgrade the token system, add depth with better surface elevation, and sharpen the type hierarchy.

**Typography**: Keep DM Sans + Fraunces + Instrument Serif — this is a premium combination. Add font-weight and size refinements for better hierarchy.

**Color system**: Formalize the existing colors as semantic tokens. Green as primary brand, blue as informational, amber as warning, red as danger.

**Spacing**: Introduce an 8px base spacing scale (4, 8, 12, 16, 20, 24, 32, 48).

**Component consistency**: Apply the existing `ck-` patterns more consistently across all panels.

---

## Risk Areas

- `UnifiedWorkspacePage.tsx` — very large file with complex panel state. Touch only the shell/nav portions, not the panel state.
- `HomePanel.tsx` — 1300+ lines, complex state management. Minimal CSS-only improvements only.
- `AttendancePanel.tsx` — complex toggle logic. Only improve visual layer, not logic.
- Font loading — Google Fonts import already in place. Adding new weights may slow load.
- CSS specificity — the single-file architecture means specificity conflicts are possible when adding new rules.

---

## Files to Update

### New files to create:
- `frontend/src/styles/tokens.css` — semantic CSS token aliases
- `frontend/src/styles/skeleton.css` — skeleton/shimmer loading component
- `frontend/src/components/ui/Skeleton.tsx` — skeleton React component
- `frontend/src/components/ui/DrawerShell.tsx` — consistent drawer wrapper
- `frontend/src/components/ui/PageHeader.tsx` — consistent page header
- `frontend/src/components/ui/SectionCard.tsx` — consistent section card wrapper
- `frontend/src/components/ui/EmptyState.tsx` — polished empty state

### Existing files to improve:
- `frontend/src/styles.css` — extend with new patterns, fix inconsistencies
- `frontend/src/pages/UnifiedWorkspacePage.tsx` — sidebar/topbar visual only
- `frontend/src/pages/workspace/ui.tsx` — add more helper components
- `frontend/src/pages/workspace/panels/StudentsPanel.tsx` — visual improvements
- `frontend/src/pages/workspace/panels/FeesPanel.tsx` — visual improvements
- `frontend/src/pages/workspace/panels/AttendancePanel.tsx` — visual improvements
- `frontend/src/pages/workspace/panels/FirefightingDashboardPanel.tsx` — improvements
- `frontend/src/pages/workspace/panels/AddStudentPanel.tsx` — form grouping
- `frontend/src/shared/components/DataTable.tsx` — skeleton + empty state
- `frontend/src/components/Modal.tsx` — mobile improvements

### Files to leave mostly untouched:
- `frontend/src/pages/workspace/panels/HomePanel.tsx` — already high quality
- `frontend/src/pages/workspace/panels/PlanningPanel.tsx` — already well designed
- `frontend/src/pages/workspace/panels/CatalogPanel.tsx` — functional, minor polish
- `frontend/src/contexts/AuthContext.tsx` — no UI concerns
- All API/service files

---

## Implementation Plan

### Pass 1: Design Tokens + Base CSS (no risk, foundational)
Create `tokens.css` with semantic aliases, extend `styles.css` with skeleton and improved patterns.

### Pass 2: Sidebar + Topbar (medium risk — shell only, not panel state)
Improve sidebar visual quality, add mobile drawer behavior, improve topbar.

### Pass 3: Shared UI Components (low risk — new components)
Create Skeleton, EmptyState, DrawerShell, PageHeader, SectionCard components.

### Pass 4: Dashboard / Home Panel (minimal changes — already good)
Minor layout polish, better section headers, skeleton loading for the KPI section.

### Pass 5: Students Panel (medium risk)
Visual improvements to table, avatar, filter bar, form grouping.

### Pass 6: Fees Panel (medium risk)
Progress card, status treatment, money formatting consistency.

### Pass 7: Attendance Panel (medium risk)
Class grid cards with progress indicators, better status.

### Pass 8: Firefighting Panel (low risk)
Better urgency cards, status timeline, quotation comparison.

### Pass 9: Supply OS / Orders Panel (low risk)
Order status pipeline, category card improvements.

### Pass 10: RBAC + Reports + Notifications (low risk)
Filter bars, timeline style audit log, delivery status badges.

### Pass 11: Responsive + Accessibility Audit (medium risk)
Mobile drawer for sidebar, table overflow handling, focus states.

### Pass 12: Build verification + cleanup
`npm run build`, fix TypeScript errors, remove unused CSS.

---

## Design Acceptance Checklist

- [ ] App does not look like a generic template
- [ ] Dashboard looks like a premium command center
- [ ] All modules use consistent cards, tables, buttons, badges, filters
- [ ] Page headers are consistent
- [ ] Forms are organized and readable
- [ ] Tables are scannable with right-aligned money
- [ ] Statuses are visually clear without color alone
- [ ] Empty states look intentional
- [ ] Loading states use skeleton shimmer
- [ ] Mobile layout is usable (sidebar collapses to drawer)
- [ ] Colors consistent via tokens
- [ ] Spacing consistent via 8px scale
- [ ] Typography hierarchy consistent
- [ ] CTAs are clear
- [ ] `npm run build` passes with 0 errors
