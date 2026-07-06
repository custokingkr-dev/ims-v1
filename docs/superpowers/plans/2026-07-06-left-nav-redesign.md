# Left-Nav Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the workspace left nav — a hover-to-expand icon rail, collapsible sub-groups, a refined active state, and uniform lucide icons.

**Architecture:** Data-driven from `config.ts` role sections (each section becomes a collapsible accordion group; the admin "School ERP" list is split into four groups). The render adds accordion open/close state (localStorage) + per-item aria labels + a collapsed monogram. All collapse/expand visuals are **CSS-driven off `:hover`/`:focus-within`** — the JSX always renders full markup; CSS clips it to an icon rail when not hovered.

**Tech Stack:** React 18 + Vite + TS, lucide-react (already a dep), plain CSS.

## Global Constraints

- **Frontend only** — no backend/API/data/permission changes. Nav visibility stays driven by the existing role sections + module gating.
- **No automated tests this pass** (per decision) — verify by `npm run build` + a manual dev check.
- **Hover-to-expand rail is desktop-only** (`min-width: 821px`); at `≤820px` the existing full-width **drawer** behavior (menu toggle + backdrop + close button, from `sidebar.css`) must be preserved unchanged.
- **All item icons stay reachable in the collapsed rail:** items are always rendered; a *closed* accordion group hides its items **only in the expanded (hovered) view**, never in the collapsed icon rail.
- **Accessibility:** every nav item has `aria-label` + `title`; the rail expands on `focus-within`; group headers are `<button aria-expanded>`; `prefers-reduced-motion` disables the width/expand animation; visible `focus-visible` outline.
- Active style stays **accent bar + soft tint** (`--g`/`--g1`), refined — do not switch to a pill.

---

### Task 1: Config groups + missing icon

**Files:**
- Modify: `frontend/src/pages/workspace/config.ts`
- Modify: `frontend/src/shared/display/icons.tsx`

**Interfaces:**
- Produces: `ADMIN_NAV_SECTIONS` split into Overview/Academics/Finance/Setup groups (Supply OS + Urgent Procurement unchanged); `classsetup` mapped to a lucide icon. The section shape is unchanged (`{title, fire?, items[]}`) — grouping = more sections.

- [ ] **Step 1: Add the `classsetup` icon**

In `icons.tsx`, add `School` to the lucide import and one map entry:

```tsx
// add School to the existing lucide import list
import {
  LayoutDashboard, GraduationCap, IndianRupee, SlidersHorizontal,
  CalendarCheck, Clock, UserPlus, FileUp, Users, Package, ShoppingCart,
  CalendarDays, AlertCircle, Plus, ClipboardCheck, Truck,
  ClipboardList, FilePlus, Receipt, Building2, BarChart2,
  TrendingUp, Globe, Package2, School,
} from 'lucide-react';
```
Add to `PANEL_ICONS`:
```tsx
  classsetup:      School,
```
Now every `PanelKey` renders a lucide icon; the `config.ts` `icon` strings become unused fallbacks (harmless — leave them).

- [ ] **Step 2: Split the admin "School ERP" section into groups**

In `config.ts`, replace the single `School ERP` section of `ADMIN_NAV_SECTIONS` with four grouped sections (keep the existing `Supply OS` and `Urgent Procurement` sections above it exactly as they are):

```ts
  {
    title: 'Overview',
    items: [
      { key: 'home', label: 'Dashboard', icon: '◼' },
    ],
  },
  {
    title: 'Academics',
    items: [
      { key: 'students',   label: 'Students',    icon: '🎓', module: 'STUDENTS' },
      { key: 'addstudent', label: 'Add student', icon: '➕', module: 'STUDENTS' },
      { key: 'bulkimport', label: 'Bulk import', icon: '📥', module: 'STUDENTS' },
      { key: 'attendance', label: 'Attendance',  icon: '✓',  module: 'ATTENDANCE' },
      { key: 'timetable',  label: 'Timetable',   icon: '📅' },
    ],
  },
  {
    title: 'Finance',
    items: [
      { key: 'fees',         label: 'Fee Collections',   icon: '₹',  module: 'FEES' },
      { key: 'feestructure', label: 'Fee Configuration', icon: '📐', module: 'FEES' },
    ],
  },
  {
    title: 'Setup',
    items: [
      { key: 'staff',      label: 'Staff & HR',            icon: '👥' },
      { key: 'classsetup', label: 'Class & section setup', icon: '🏫' },
    ],
  },
```

(Other roles' `*_NAV_SECTIONS` are unchanged — they already have short titled sections and will render as the same collapsible-group component.)

- [ ] **Step 3: Build**

Run:
```bash
cd frontend
npm run build
```
Expected: build succeeds (types unchanged; only data + one icon map entry).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/workspace/config.ts frontend/src/shared/display/icons.tsx
git commit -m "feat(nav): split admin School ERP into groups + map classsetup icon"
```

---

### Task 2: Render collapsible groups + collapsed markup + a11y

**Files:**
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx`

**Interfaces:**
- Consumes: Task 1's grouped sections.
- Produces: accordion group render (open/close state persisted in localStorage), per-item `aria-label`/`title`, a collapsed-rail monogram, `ck-nav-label`/`ck-nav-group-*`/`ck-sb-monogram` hooks for Task 3's CSS.

- [ ] **Step 1: Accordion open/close state (localStorage)**

Add near the other `useState` (the `ChevronDown` icon comes from lucide, already used for other icons in this file — import it):

```tsx
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() => {
    try { return JSON.parse(localStorage.getItem('ck_nav_groups') || '{}'); } catch { return {}; }
  });
  const toggleGroup = (title: string) => {
    setOpenGroups((prev) => {
      const next = { ...prev, [title]: !(prev[title] ?? true) };
      try { localStorage.setItem('ck_nav_groups', JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  };
```
Add the import: `import { X, ChevronDown } from 'lucide-react';` (keep any existing lucide imports on that line).

- [ ] **Step 2: Rewrite the `nav.ck-nav` section render**

Replace the `navSections.map(...)` block (currently `UnifiedWorkspacePage.tsx:303-330`) with grouped, always-rendered items (a *closed* group gets a `closed` class; items are NOT conditionally removed — CSS hides them only when expanded):

```tsx
        <nav className="ck-nav">
          {navSections.map((section) => {
            const open = openGroups[section.title] ?? true;
            return (
              <div key={section.title} className={`ck-nav-group${open ? '' : ' closed'}${section.fire ? ' fire' : ''}`}>
                <button
                  type="button"
                  className="ck-nav-group-header"
                  aria-expanded={open}
                  onClick={() => toggleGroup(section.title)}
                >
                  <span className="ck-nav-group-title">{section.fire ? 'Urgent Procurement' : section.title}</span>
                  <ChevronDown className="ck-nav-chevron" size={13} aria-hidden />
                </button>
                {section.items.map((item) => (
                  <button
                    key={item.key}
                    className={`ck-nav-item ${panel === item.key ? 'on' : ''} ${section.fire ? 'fire' : ''}`}
                    onClick={() => { setPanel(item.key); setSidebarOpen(false); }}
                    title={item.label}
                    aria-label={item.label}
                    aria-current={panel === item.key ? 'page' : undefined}
                  >
                    <NavIcon panelKey={item.key} fallback={item.icon} />
                    <span className="ck-nav-label">{item.label}</span>
                    {item.key === 'sa-invoices' && saInvBadge > 0 && (
                      <span className="ck-nav-badge">{saInvBadge}</span>
                    )}
                  </button>
                ))}
              </div>
            );
          })}
        </nav>
```

(The old `ck-fire-header` callout and `ck-sep` are replaced by the group header + CSS; the fire group keeps its orange treatment via the `.fire` class in Task 3.)

- [ ] **Step 3: Collapsed-rail header monogram**

In `.ck-sb-header` (currently the logo + school name block, `UnifiedWorkspacePage.tsx:284-300`), add a monogram element that CSS shows only when collapsed (place it as the first child):

```tsx
        <div className="ck-sb-header">
          <div className="ck-sb-monogram" aria-hidden>CK</div>
          <div className="ck-sb-logo">custoking</div>
          <div className="ck-school-name">{workspace.school.name}</div>
          <div className="ck-school-meta">{workspace.school.meta}</div>
          {workspace?.school?.name && (
            <div className="ck-sb-school-badge">{workspace.school.name}</div>
          )}
          <button className="ck-sb-close" onClick={() => setSidebarOpen(false)} aria-label="Close navigation menu">
            <X size={17} strokeWidth={2} aria-hidden />
          </button>
        </div>
```

- [ ] **Step 4: Wrap user-card details for collapse**

In `.ck-user-card` (`UnifiedWorkspacePage.tsx:333-351`), keep the avatar always visible and wrap the name/role/Sign-out so CSS can hide them when collapsed. Add class `ck-user-card-details` around the name/meta block and the badge row (the avatar stays a direct child, centered when collapsed):

```tsx
        <div className="ck-user-card">
          <div className="ck-user-card-inner">
            <div className="ck-user-avatar" aria-hidden="true">
              {(user?.fullName ?? user?.email ?? 'U').charAt(0).toUpperCase()}
            </div>
            <div className="ck-user-card-details">
              <div className="ck-user-name">{user?.fullName ?? user?.email}</div>
              <div className="ck-user-meta">{role?.replace('_', ' ') ?? 'User'}</div>
            </div>
          </div>
          <div className="ck-badge-row ck-user-card-details" style={{ marginTop: 10 }}>
            <button className="ck-btn ck-btn-ghost ck-btn-sm"
              onClick={() => { logout(); navigate('/login', { replace: true }); }}>
              Sign out
            </button>
          </div>
        </div>
```

- [ ] **Step 5: Build**

Run: `cd frontend && npm run build` → succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "feat(nav): collapsible group render + a11y labels + collapsed monogram/user-card"
```

---

### Task 3: Hover-expand rail + refined styles (CSS)

**Files:**
- Modify: `frontend/src/styles.css` (the `.ck-sidebar`/`.ck-nav*` block ~115-131)
- Modify: `frontend/src/styles/sidebar.css` (mobile drawer — gate the rail to desktop)

**Interfaces:**
- Consumes: Task 2's `ck-nav-group`/`ck-nav-group-header`/`ck-nav-title`/`ck-nav-chevron`/`ck-nav-label`/`ck-sb-monogram`/`ck-user-card-details` hooks.

- [ ] **Step 1: Desktop hover-expand rail + refined nav (replace the `styles.css:115-131` block)**

Replace the current `.ck-sidebar` … `.ck-user-name` rules with:

```css
/* ── Left nav: hover-to-expand icon rail (desktop) ───────────────────────── */
.ck-sidebar {
  width: 64px;
  background: #fff;
  border-right: 1px solid var(--border);
  display: flex; flex-direction: column;
  position: fixed; top: 0; left: 0; bottom: 0;
  overflow-x: hidden; overflow-y: auto;
  transition: width .18s ease;
  z-index: 40;
}
.ck-sidebar:hover, .ck-sidebar:focus-within {
  width: 248px;
  box-shadow: 2px 0 16px rgba(0,0,0,.08);
}
.ck-main { margin-left: 64px; }

.ck-sb-header { padding: 16px 12px 12px; border-bottom: 1px solid var(--border); position: relative; }
.ck-sb-monogram {
  width: 40px; height: 40px; border-radius: 10px;
  background: var(--g1); color: var(--g);
  display: flex; align-items: center; justify-content: center;
  font: 700 15px 'Fraunces', serif; letter-spacing: -.02em;
}
.ck-sb-logo { font: 400 19px 'Fraunces', serif; color: var(--g); letter-spacing: -.02em; }
.ck-school-name { margin-top: 6px; font-size: 12px; font-weight: 700; }
.ck-school-meta { font-size: 11px; color: var(--ink3); }
/* collapsed: show monogram, hide wordmark/name/badge */
.ck-sb-logo, .ck-school-name, .ck-school-meta, .ck-sb-school-badge { opacity: 0; transition: opacity .12s ease; }
.ck-sidebar:hover .ck-sb-logo, .ck-sidebar:hover .ck-school-name,
.ck-sidebar:hover .ck-school-meta, .ck-sidebar:hover .ck-sb-school-badge { opacity: 1; }
.ck-sidebar:hover .ck-sb-monogram { display: none; }
.ck-sidebar:not(:hover) .ck-sb-logo,
.ck-sidebar:not(:hover) .ck-school-name,
.ck-sidebar:not(:hover) .ck-school-meta,
.ck-sidebar:not(:hover) .ck-sb-school-badge { height: 0; margin: 0; overflow: hidden; }

.ck-nav { padding: 8px 0 16px; display: flex; flex-direction: column; gap: 2px; flex: 1; }

/* group header (accordion) */
.ck-nav-group-header {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  width: 100%; border: none; background: transparent; cursor: pointer;
  padding: 12px 16px 4px; color: var(--ink3);
  font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: .08em;
}
.ck-nav-group-header:hover { color: var(--ink2); }
.ck-nav-chevron { transition: transform .15s ease; flex-shrink: 0; }
.ck-nav-group.closed .ck-nav-chevron { transform: rotate(-90deg); }
.ck-nav-group.fire .ck-nav-group-header { color: var(--or); }
/* headers hidden in the collapsed rail */
.ck-sidebar:not(:hover) .ck-nav-group-header { height: 0; padding: 0; overflow: hidden; opacity: 0; }
.ck-sidebar:not(:hover) .ck-nav-group:not(:first-child) { border-top: 1px solid var(--border); margin-top: 4px; padding-top: 4px; }

/* nav item */
.ck-nav-item {
  display: flex; align-items: center; gap: 11px; width: 100%;
  border: none; background: transparent; text-align: left;
  padding: 9px 16px; color: var(--ink2); cursor: pointer;
  border-left: 2px solid transparent; white-space: nowrap;
}
.ck-nav-item > svg { flex-shrink: 0; }
.ck-nav-label { transition: opacity .12s ease; }
.ck-sidebar:not(:hover) .ck-nav-item { justify-content: center; padding: 9px 0; gap: 0; }
.ck-sidebar:not(:hover) .ck-nav-label { opacity: 0; width: 0; overflow: hidden; }
.ck-nav-item:hover { background: var(--bg); }
.ck-nav-item.on { background: var(--g1); color: var(--g); border-left-color: var(--g); font-weight: 600; }
.ck-nav-item.on > svg { color: var(--g); }
.ck-nav-item.fire.on { background: var(--or1); color: var(--or); border-left-color: var(--or); }
.ck-nav-item.fire.on > svg { color: var(--or); }
.ck-nav-item:focus-visible { outline: 2px solid var(--g); outline-offset: -2px; }

/* accordion: a closed group hides its items ONLY when expanded (rail keeps all icons) */
.ck-sidebar:hover .ck-nav-group.closed .ck-nav-item { display: none; }

/* user card */
.ck-user-card { margin: 12px; padding: 12px; border-radius: 12px; border: 1px solid var(--border); background: #faf9f6; }
.ck-user-name { font-weight: 700; margin-bottom: 4px; }
.ck-user-card-details { transition: opacity .12s ease; }
.ck-sidebar:not(:hover) .ck-user-card { margin: 12px 10px; padding: 8px; }
.ck-sidebar:not(:hover) .ck-user-card-details { opacity: 0; height: 0; overflow: hidden; margin: 0; }
.ck-sidebar:not(:hover) .ck-user-card-inner { justify-content: center; }

@media (prefers-reduced-motion: reduce) {
  .ck-sidebar, .ck-nav-label, .ck-nav-chevron, .ck-user-card-details,
  .ck-sb-logo, .ck-school-name, .ck-school-meta { transition: none; }
}
```

> If any of `--or`/`--or1` (fire orange) aren't defined, reuse the existing fire tokens the old
> `.ck-nav-item.fire.on` used (the codebase already references `var(--or1)`/`var(--or)` in the
> current rules, so they exist).

- [ ] **Step 2: Gate the rail to desktop; keep the mobile drawer**

The existing mobile drawer lives in `sidebar.css` under `@media (max-width: 820px)` (fixed 272px drawer, `transform: translateX(-100%)`, `.open` slides in). Ensure the desktop hover-rail does NOT fight it: inside that `@media (max-width: 820px)` block (append rules), force the drawer shape and disable hover-expand:

```css
@media (max-width: 820px) {
  /* drawer: always full labels, no icon-rail, no hover-expand */
  .ck-sidebar { width: 272px !important; }
  .ck-sidebar:hover { box-shadow: var(--ck-shadow-popover); }
  .ck-main { margin-left: 0 !important; }
  .ck-nav-label, .ck-nav-group-header, .ck-sb-logo, .ck-school-name,
  .ck-school-meta, .ck-sb-school-badge, .ck-user-card-details { opacity: 1 !important; height: auto !important; }
  .ck-sb-monogram { display: none; }
  .ck-nav-item { justify-content: flex-start !important; padding: 9px 16px !important; gap: 11px !important; }
  .ck-nav-label { width: auto !important; overflow: visible !important; }
  .ck-sidebar:not(:hover) .ck-nav-group-header { height: auto; padding: 12px 16px 4px; opacity: 1; }
}
```

Also check `styles.css` for a stray `.ck-sidebar { position:static; width:auto }` inside another media query (~line 271) — if it conflicts with the new rail at desktop widths, scope/remove it so the desktop rail rule wins; the mobile drawer (≤820px) must remain the drawer. Report what you found/changed.

- [ ] **Step 3: Build + manual smoke**

Run: `cd frontend && npm run build` → succeeds. Then note (for the report) the manual checks to run on the dev server: collapsed icon rail; hover expands to labels + group headers; clicking a group header collapses it (and the choice persists on reload); active item shows the accent bar in both states; a closed group's icons still show in the collapsed rail; the fire group stays orange; the mobile drawer (≤820px) opens with full labels; keyboard tab into the nav expands it (focus-within).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/styles.css frontend/src/styles/sidebar.css
git commit -m "feat(nav): hover-expand icon rail + refined active/group styles + mobile-drawer guard"
```

---

## Self-Review Notes

- **Spec coverage:** hover-expand rail (T3 CSS), collapsible sub-groups + admin split (T1 config, T2 render, T3 accordion CSS), refined accent-bar active state (T3), consistent lucide icons (T1 classsetup), collapsed monogram/user-card (T2 markup + T3 CSS), a11y + reduced-motion + mobile drawer (T2 aria, T3 media/motion). All spec sections covered.
- **"All icons in collapsed rail":** items are always rendered; `.ck-sidebar:hover .ck-nav-group.closed .ck-nav-item { display:none }` hides closed-group items only in the expanded view — the collapsed rail shows every icon.
- **No reflow on hover:** `.ck-main` keeps `margin-left:64px`; the rail overlays (fixed + raised z-index + shadow) when expanded.
- **Mobile preserved:** the `≤820px` drawer rules override the rail (full labels, no hover-expand); a stray static-sidebar rule is reconciled in T3 Step 2.
- **No tests this pass** (per decision) — `npm run build` + the manual smoke list.
- **Out of scope:** click-to-pin toggle, nav search, top-bar/panel content, per-user customization, backend.
```