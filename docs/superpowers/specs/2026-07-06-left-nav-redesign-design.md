# Left-Nav Redesign

**Date:** 2026-07-06
**Status:** Approved (design)
**Service:** `frontend` only

---

## Context

The workspace left sidebar (`UnifiedWorkspacePage.tsx` + `frontend/src/pages/workspace/config.ts`
+ `styles.css`/`sidebar.css`) is a fixed 248px panel: `custoking` logo + school badge → role-based
`nav.ck-nav` (uppercase section titles + `ck-nav-item` buttons with a green left-border active
state) → user card. Pain points: the admin **"School ERP" section is 10 flat items**; the sidebar
is always full-width (no way to reclaim space); and while icons *render* as lucide (via
`NavIcon`/`icons.tsx`), one panel (`classsetup`) still falls back to an emoji and `config.ts`
carries a dead jumble of emoji/geometric-unicode fallbacks.

## Decisions (locked during brainstorming)

- **Hover-to-expand icon rail.** Default = a narrow icon-only rail; hovering the sidebar expands
  it to full width with labels, collapsing back on mouse-leave. Desktop only.
- **Active style: accent bar + soft tint (refined).** Keep the left accent bar + soft tint, with
  tightened spacing/typography — evolve, don't replace.
- **Collapsible sub-groups.** Nav sections become labeled, collapsible accordion groups; the
  admin "School ERP" list is split into a few logical groups.
- **Consistent icons.** All items use lucide (already true except `classsetup`); add the missing
  mapping and remove the dead `config.ts` emoji.
- **Visual polish** throughout (header, items, user card, transitions).
- Frontend-only; no backend, no API, no data changes.

## Rail behavior (desktop, hover-to-expand)

- `.ck-sidebar` is `position: fixed`, **collapsed width ~64px** (icon rail). On `:hover` (or
  keyboard focus-within) it animates to **~248px** and reveals labels + group headers.
- **Overlay, not push:** `.ck-main` keeps a fixed `margin-left: 64px`; the expanded rail floats
  over the content (raised `z-index` + shadow) so hovering never reflows the page. Width
  transitions with `--ck-duration` easing; respects `prefers-reduced-motion` (no width animation,
  instant).
- **Collapsed state:** each `ck-nav-item` shows only its icon, centered; the label span is
  `opacity:0`/clipped (`overflow:hidden`), group headers hidden, active items still show the
  accent bar + tinted icon. Every item carries a `title` + `aria-label` so the icon-only rail is
  usable and screen-reader-labelled.
- **Expanded state:** icon + label, uppercase group headers with a chevron; the logo + school
  name + full user card appear (collapsed shows a compact mark + avatar-only).
- **Accordion (independent of collapse):** in the expanded view, clicking a group header
  toggles that group open/closed; state persists in `localStorage`. In the collapsed icon rail
  **all item icons always show** (grouped by thin separators) — the accordion only affects the
  labelled/expanded view, so every destination stays reachable in either state.

## Collapsible sub-groups (config restructure)

`config.ts` nav sections gain optional grouping so a role's nav is a list of **collapsible
groups**. The admin **School ERP** section is split (exact grouping to confirm at spec review):

- **Overview** — Dashboard
- **Academics** — Students, Add student, Bulk import, Attendance, Timetable
- **Finance** — Fee Collections, Fee Configuration
- **Setup** — Staff & HR, Class & section setup

`Supply OS` and `Urgent Procurement` stay their own groups (as today). Other roles
(OPERATIONS/ACCOUNTANT/TEACHER/VIEWER/ZONE_ADMIN/SUPERADMIN) keep their existing sections, now
rendered as the same collapsible-group component (their lists are short; grouping is uniform but
low-impact for them). The special orange `Urgent Procurement` callout is preserved.

## Icons

- Add `classsetup` to `PANEL_ICONS` in `src/shared/display/icons.tsx` (a lucide `School`/`LayoutGrid`).
- Remove the now-dead emoji/geometric-unicode `icon` values from `config.ts` (or keep them only as
  a harmless last-resort fallback string — the redesign renders lucide via `NavIcon` for every
  item). Nav icons render at a single consistent size/stroke.

## Visual polish

- Refined `.ck-nav-item` (spacing, hover, focus-visible ring), active accent bar + `--g1` tint +
  tinted icon + medium-weight label; consistent icon box so labels align.
- Header: full `custoking` wordmark + school name/badge when expanded; a compact monogram when
  collapsed. User card: full (avatar + name + role + Sign out) expanded; avatar-only (with the
  same actions on hover-expand) collapsed.
- Group headers: quiet uppercase + chevron; smooth expand/collapse.

## Mobile

Unchanged behavior: below the existing breakpoint the sidebar is a **full-width drawer** opened by
the menu toggle (backdrop + close button) — **no icon-rail, no hover-expand** (touch has no hover);
labels always visible. The hover-expand rail is gated to the desktop media query.

## Accessibility

- Collapsed icon items: `aria-label` + `title` (the visible label text); visible `focus-visible`
  outline; the rail also expands on `focus-within` so keyboard users see labels.
- Accordion headers are `<button aria-expanded>` toggling their group; group is a labelled region.
- `prefers-reduced-motion`: disable width/expand animations.

## Files touched

- `frontend/src/pages/workspace/config.ts` — nav sections → collapsible groups; admin School ERP
  split; drop dead emoji.
- `frontend/src/shared/display/icons.tsx` — add `classsetup`.
- `frontend/src/pages/UnifiedWorkspacePage.tsx` — render collapsible groups (accordion state +
  localStorage), per-item `aria-label`/`title`, compact header/user-card in collapsed state.
- `frontend/src/styles.css` + `frontend/src/styles/sidebar.css` — hover-expand rail (collapsed/
  expanded widths, overlay, label clip), refined active/hover/focus, group header + chevron,
  collapsed header/user-card, reduced-motion + mobile-drawer guards.

## Testing (confirm at plan handoff)

Proposed: **no automated tests** (a CSS/layout-heavy redesign) — verify by `npm run build` + a
manual dev check (hover-expand + pin, accordion collapse persists, active state, every role's nav,
mobile drawer, keyboard focus reveals labels). A light Vitest render test of the group-accordion
component is optional.

## Out of scope (later)

- A click-to-pin toggle (chosen behavior is hover-expand only; pinning can be a follow-up).
- A nav search / command palette.
- Reworking the top bar, dashboard, or any panel content.
- Per-user nav customization / reordering / favorites.
- Backend/permission changes (nav visibility stays driven by the existing role sections + module
  gating).
