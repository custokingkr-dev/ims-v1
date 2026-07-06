# Custoking IMS — Design Language Specification

**Direction:** *Slate & Signal · Dark Rail*
**Date:** 2026-07-07
**Status:** Approved (visual direction) — foundation for the frontend redesign program
**Scope:** The design *foundation* — color, type, space, elevation, density, the core
component kit, and the app shell / navigation. Per-module panel redesigns are **separate
specs** that consume this one.

---

## 1. Purpose & context

The frontend is a data-dense React + Vite + TS SPA (~60 panels across Students, Attendance,
Fees, Supply OS, Urgent Procurement, Superadmin/billing, Zone, RBAC). The current
"Warm Enterprise" beige/green identity was judged dated, insufficiently data-forward,
inconsistent panel-to-panel, and weak on color appeal.

This spec replaces that identity with a single, research-backed system so every panel is
built from the same tokens and reads as one product. It is the terminal design artifact of
the brainstorming phase; implementation is planned separately (per-module).

**Research basis** (see `docs/frontend-design-audit.md` predecessor and the redesign research
synthesis): Carbon, Linear, Vercel/Geist, Refactoring UI for density & shell; WCAG 2.2 and
OKLCH/CSS Color 4 for color; Okabe-Ito/ColorBrewer for dataviz; IBM Plex/Inter for type.

### Non-negotiable principles (inherited by every panel)

1. **One accent, spent on signal.** The workspace is near-monochrome graphite; indigo appears
   only on things the user acts on (primary CTA, active nav/row, focused field, sparkline,
   selection). Color is information, not decoration.
2. **The rail anchors.** A deep navy sidebar frames every screen; the bright workspace makes
   data pop against it.
3. **Numbers are tabular and right-aligned.** Every numeric/ID column uses tabular figures.
4. **Depth via hairlines + surface steps, not shadows.** Shadows are reserved for floating
   layers (popover, dropdown, drawer, modal).
5. **Never color-alone.** Status and chart series always pair color with an icon, label, shape,
   or position (WCAG 1.4.1).
6. **Ship to WCAG 2.2 AA numeric ratios:** 4.5:1 body text, 3:1 large text and all UI
   borders/icons/focus rings. APCA used as a dark-mode sanity check only.

---

## 2. Color system

Built in **OKLCH** with a shared lightness ladder per hue so any two hues at the same step
carry the same perceived brightness and pass/fail contrast identically. Values below are the
resolved sRGB hex (production authored in OKLCH, hex is the fallback). Every text-on-surface
pairing listed meets AA.

### 2.1 Token architecture (3 tier)

`primitive` (raw ramp step, e.g. `--p-indigo-600`) → `semantic/alias` (role, e.g.
`--color-accent`) → `component` (e.g. `--btn-primary-bg`). Delivered as CSS custom properties.

**Migration note:** the existing `frontend/src/styles/tokens.css` already exposes a semantic
`--ck-*` layer. We **redefine the `--ck-*` values** to the new palette (so existing consumers
inherit the new look with no markup change) and **add** the new tokens this spec introduces
(rail, density, chart-dark, focus ring). The cryptic legacy vars (`--g`, `--b`, `--am`, `--re`,
`--bg`, `--ink`) are kept as thin aliases pointing at the new primitives during migration, then
retired panel-by-panel. No panel is required to change markup to adopt the new palette.

### 2.2 Light theme — semantic tokens

| Token | Hex | OKLCH (approx) | Role / contrast |
|---|---|---|---|
| `--bg-canvas` | `#F6F7F9` | 0.972 0.003 265 | App workspace background |
| `--bg-surface` | `#FFFFFF` | 1.000 0 0 | Cards, tables, panels |
| `--bg-raised` | `#EFF1F5` | 0.951 0.005 265 | Inset / hovered / raised fills |
| `--bg-overlay` | `rgba(20,24,31,.40)` | — | Modal/drawer scrim |
| `--text-primary` | `#14181F` | 0.235 0.010 260 | Body/ink — 16:1 on surface |
| `--text-secondary` | `#555D68` | 0.470 0.017 260 | Secondary — 7.0:1 |
| `--text-muted` | `#88909B` | 0.650 0.017 260 | Labels/captions — 3.4:1 (large/UI only) |
| `--border-subtle` | `rgba(20,24,31,.085)` | — | Hairlines, gridlines, dividers |
| `--border-default` | `rgba(20,24,31,.14)` | — | Inputs, buttons, card edges |
| `--accent` | `#4B57D2` | 0.520 0.180 268 | Primary indigo — 5.6:1 on surface |
| `--accent-ink` | `#3A46C0` | 0.455 0.185 268 | Accent text/links — 6.9:1 |
| `--accent-soft` | `#EBEDFB` | 0.945 0.020 268 | Accent fill (active row, soft btn) |
| `--accent-on` | `#FFFFFF` | — | Text on solid accent — 5.6:1 |

### 2.3 Light theme — the rail (navy chrome)

| Token | Hex | Role |
|---|---|---|
| `--rail-bg` | `#161B2C` | Sidebar background |
| `--rail-text` | `#E8EAF4` | Nav item text — 13.6:1 on rail |
| `--rail-text-muted` | `#98A0BD` | Section headers, school line — 4.6:1 |
| `--rail-text-faint` | `#7982A1` | Eyebrows / uppercase group labels — 3.2:1 (large) |
| `--rail-active-bg` | `rgba(99,110,230,.22)` | Active item fill |
| `--rail-active-text` | `#BCC2F6` | Active item text — 8.9:1 on rail |
| `--rail-border` | `rgba(255,255,255,.08)` | Rail dividers |
| `--rail-badge-bg` | `rgba(255,255,255,.12)` | Count badge |
| `--rail-badge-text` | `#CDD2EA` | Count badge text |

### 2.4 Reserved status colors (light) — separate from chart hues

Always shipped with an icon + label, never color-alone. Meet 3:1 as UI, text pairs meet 4.5:1.

| Role | Fg | Soft bg | Use |
|---|---|---|---|
| Success / Paid | `#0F7A5A` | `#E2F2EB` | Paid, healthy, completed |
| Warning / Partial | `#A9760A` | `#F5EDD9` | Partial, due-soon, attention |
| Danger / Overdue | `#C23A2B` | `#FBE7E4` | Overdue, error, destructive |
| Info | `#1D6FD0` | `#E7F0FC` | Neutral notices, in-progress |

Delta indicators: **up** uses Success, **down** uses Danger — always with an arrow glyph
(`▲`/`▼`), never color-alone.

### 2.5 Dark theme — same system, own steps (not an inversion)

Surface never pure black; depth via lightness-stepped surfaces.

| Token | Hex |
|---|---|
| `--bg-canvas` | `#111318` |
| `--bg-surface` | `#181B21` |
| `--bg-raised` | `#1F232A` |
| `--text-primary` | `#ECEEF2` (14.5:1) |
| `--text-secondary` | `#A3A9B4` (7.1:1) |
| `--text-muted` | `#6E7581` (3.5:1, UI/large) |
| `--border-subtle` | `rgba(255,255,255,.09)` |
| `--border-default` | `rgba(255,255,255,.15)` |
| `--accent` | `#7B84E8` (lightened indigo — 6.4:1 on surface) |
| `--accent-ink` | `#AAB0F2` (links — 9.0:1) |
| `--accent-soft` | `rgba(123,132,232,.16)` |
| `--rail-bg` | `#0F131E` (rail goes a touch darker than workspace surface) |
| Success | `#3FB488` / soft `rgba(63,180,136,.16)` |
| Warning | `#D3A24A` / soft `rgba(211,162,74,.16)` |
| Danger | `#E8705C` / soft `rgba(232,112,92,.16)` |
| Info | `#6EA8F0` / soft `rgba(110,168,240,.16)` |

Theme switching: `prefers-color-scheme` sets the default; an explicit user toggle stamps
`data-theme="light|dark"` on `:root`, which overrides the media query in both directions.

### 2.6 Data-visualization palettes (validated — do not hand-edit)

Categorical, colorblind-safe (Okabe-Ito derived), max 6 categories, **always direct-labeled**.
Both palettes passed the dataviz validator (lightness band, chroma floor, CVD ≥12 adjacent
separation, contrast). A 7th+ category folds into "Other" or uses small multiples.

**Light** (`--chart-1..6`): `#0072B2 #E69F00 #009E73 #CC79A7 #56B4E9 #D55E00`
**Dark** (`--chart-1..6`): `#2E86C4 #B57C0E #12A87E #C06B99 #3F9AD1 #DD6B3A`

Fixed module→hue mapping (stable across the app, so a series never changes color):

| Slot | Module | Light | Dark |
|---|---|---|---|
| 1 | Fees / Finance | `#0072B2` | `#2E86C4` |
| 2 | Supply / Orders | `#E69F00` | `#B57C0E` |
| 3 | Students | `#009E73` | `#12A87E` |
| 4 | Firefighting / Urgent | `#CC79A7` | `#C06B99` |
| 5 | Attendance | `#56B4E9` | `#3F9AD1` |
| 6 | Other | `#D55E00` | `#DD6B3A` |

- **Sequential** (one measure, low→high): single-hue ramp of `--accent` (indigo) or the slot's
  hue, light→dark, monotonic lightness.
- **Diverging** (vs a target/zero): Danger ↔ neutral gray ↔ Success, gray midpoint.
- Rules: vary lightness not just hue; never a dual-axis chart; recessive grid/axes; thin marks;
  2px surface gap between adjacent/stacked fills; validate any new palette before shipping.

---

## 3. Typography

**Production face:** **IBM Plex Sans** (UI + data) — tall x-height, ships `tnum` and a slashed
zero to disambiguate `0/O` in admission numbers, roll numbers, and IDs. Loaded self-hosted
(woff2) to avoid CDN dependency. **Mono:** IBM Plex Mono, reserved for log/code/raw-ID columns
only. No separate display serif in-app (a serif may appear on marketing surfaces only).

> Interim: system stack `-apple-system, 'Segoe UI', system-ui, sans-serif` until Plex is
> bundled. All numeric elements get `font-variant-numeric: tabular-nums lining-nums`.

### Scale (hand-tuned, tight ratio)

| Token | px / line-height | Use |
|---|---|---|
| `--text-xs` | 12 / 1.4 | Captions, badges, table meta |
| `--text-sm` | 14 / 1.35 | **Default UI** — table cells, controls, labels |
| `--text-base` | 16 / 1.5 | Running prose only (help text, empty-state body) |
| `--text-lg` | 18 / 1.35 | Card titles, sub-headings |
| `--text-xl` | 20 / 1.25 | Panel section heads |
| `--text-2xl` | 24 / 1.2 | Page/greeting heads |
| `--text-3xl` | 30 / 1.15 | Dashboard hero / rare |

Weights: 400 body · 500 controls/labels · 600 emphasis/headers · 700 numbers & titles.
Never below **12px**. Prose containers capped at `max-width: 66ch`; tables exempt.

---

## 4. Spacing, radius, density, elevation

- **Spacing (8pt grid + 4px sub-grid):** `4 · 8 · 12 · 16 · 24 · 32 · 48 · 64`. More space
  *around* a group than within it. Off-scale values are not allowed.
- **Radius:** `--r-sm 6` (inputs, badges, buttons) · `--r-md 8` (cards, menus) · `--r-lg 12`
  (modals, large surfaces) · `--r-pill 999`. Corners are moderate, not maximal.
- **Density (persisted per user via `data-density` on `:root`):** row heights
  **32 compact / 40 default / 48 comfortable**. Default 40; 48 offered on tablet/touch. Never
  below 32 for interactive rows. Icon-only actions honor WCAG 2.5.8 (24×24 min incl. padding).
- **Elevation:** depth via `--border-subtle` hairlines (~8–9% alpha) + the 3-step surface ladder
  (canvas→surface→raised). Shadow tokens only for floating layers:
  `--shadow-popover: 0 8px 24px rgba(20,24,31,.12), 0 2px 8px rgba(20,24,31,.06)`,
  `--shadow-drawer: -4px 0 24px rgba(20,24,31,.10)`.
- **Focus ring:** tokenized `2px` solid `--accent` (dark: `--accent`) at ≥3:1 on every surface,
  via `:focus-visible`. Never removed without a replacement indicator.
- **Motion:** durations `--dur-fast 120ms / --dur-base 180ms / --dur-slow 300ms`, ease
  `cubic-bezier(.2,.8,.2,1)`. All transitions gated behind `prefers-reduced-motion`.

---

## 5. Component specifications

Each is a shared React component under `frontend/src/components/ui/` (new) or an existing
`workspace/ui.tsx` helper, driven only by tokens. Detailed prop APIs land in the implementation
plan; this fixes the *design contract*.

### 5.1 App shell — nav rail
- Fixed left rail, **216–222px** on desktop; collapsible to a **56px icon rail** on tablet /
  when the user pins it collapsed (persisted, reuses existing `ck_nav_pinned`).
- Structure: brand wordmark → active school line → grouped nav sections (uppercase faint
  headers, `--rail-text-faint`) → spacer → user chip. Groups collapsible (reuses existing
  `openGroups` state). Active item: `--rail-active-bg` fill + `--rail-active-text`.
- Count badges right-aligned; urgent badge uses a danger-tinted variant on the rail.
- Section grouping follows `config.ts` (Overview / Academics / Finance / Supply OS / Urgent
  Procurement / Setup), unchanged per role.

### 5.2 Topbar
- **52px**, surface bg, bottom hairline. Left: breadcrumb (`Panel · context`). Right:
  **⌘K command palette trigger** (search + jump-to, table-stakes power-user nav), primary
  `＋ New` CTA (solid accent), notification bell, avatar.
- Breadcrumbs appear for deep drill-downs (e.g. Student → Fees).

### 5.3 KPI / stat card
- Four layers: **label** (xs, muted, uppercase) → **value** (2xl, 700, tabular) with an inline
  **delta** chip (abs + arrow, success/danger soft) → **sub** context (target / denominator) →
  **sparkline** (accent line, emphasized endpoint dot). 4–6 across the dashboard top row.
- Delta shows both absolute and %; icon + color, never color-alone.

### 5.4 Data table
- Header row: uppercase xs muted labels, bottom hairline; **sticky header + frozen first
  (name) column** when content overflows. Rows at the active density height, hover highlight
  (`--bg-canvas`/raised), 1px subtle row separators, optional zebra off by default.
- **Numbers right-aligned, tabular.** Text left. Status via pills (§5.6).
- Row actions: 1–2 inline + overflow (`…`) menu; bulk-select checkboxes reveal on hover.
- **Edit opens a non-modal side panel** (context rows stay visible), not a full modal.
- **Server-side pagination** with "records 31–40 of 200"; no infinite scroll for structured data.
- Grid engine target (impl detail, non-binding here): TanStack Table + Virtual, virtualize past
  ~100 rows; ARIA grid roving-tabindex keyboard nav.

### 5.5 Buttons
- **Primary:** solid `--accent`, `--accent-on` text, `--r-sm`, 14px/500, 32/36/40px heights per
  density. **Secondary:** surface + `--border-default`. **Ghost:** text-only, accent on hover.
  **Danger:** solid Danger. Disabled: 40% opacity, no pointer.

### 5.6 Status pill / chip
- Dot + label, xs/700, soft-bg + fg from the reserved status set. Fixed vocabulary: Paid /
  Partial / Overdue / Pending / Info. API `ALL_CAPS` values are prettified to sentence case at
  the display layer.

### 5.7 Priority-queue / list item
- Leading status dot (status color) → title (sm/600) + subtext (xs muted) → trailing action link
  (accent). Hover fill. Used on the dashboard and any "needs attention" list.

### 5.8 Empty, loading, error states (thresholds)
- **<1s:** nothing. **2–10s:** **skeletons** for tables/cards/dashboards (mirror final layout),
  **spinners** for short discrete actions (save/pay/auth). **>10s:** progress bar.
- **Empty:** never blank — a one-line explanation + a primary next action, in the interface's
  voice.
- **Error:** designed card — what happened + how to fix + retry. No apologies, no vagueness.

### 5.9 Command palette (⌘K)
- Global search + jump-to-panel + quick actions. Opens on `⌘K`/`Ctrl-K`. Fuzzy match over
  nav items, students, orders. Recessive by default; the primary nav surface for power users.

### 5.10 Forms & side panel
- Section-grouped (divider + section label), label above field, sm labels. Multi-column form
  grids collapse responsively (existing `ck-fg-*` behavior, tuned for a tablet mid-step).
  Inline validation with the reserved Danger color + message.

---

## 6. Token delivery & file plan (design contract, not the build plan)

- `frontend/src/styles/tokens.css` — extend: add primitives + new semantic/rail/chart-dark/
  density/focus tokens; redefine `--ck-*` to new values; keep legacy shorthand vars as aliases.
- New: `frontend/src/styles/theme-dark.css` (or `:root[data-theme=dark]` block) — dark steps.
- New shared components under `frontend/src/components/ui/`: `NavRail`, `Topbar`, `StatCard`,
  `DataTable` (extend existing `shared/components/DataTable.tsx`), `StatusPill`, `Button`,
  `SidePanel`, `EmptyState`, `Skeleton`, `CommandPalette`.
- Fonts: self-hosted IBM Plex Sans/Mono woff2 under `frontend/src/assets/fonts/` + `@font-face`.
- The shell (`UnifiedWorkspacePage.tsx`) adopts `NavRail`/`Topbar` visually only — **no panel
  state logic is moved** (that file is flagged high-risk).

---

## 7. Scope boundaries

**In scope (this spec):** the token system, typography, spacing/density/elevation, the app shell
(rail + topbar), and the shared component kit + their visual contracts.

**Out of scope (separate specs, one per batch):** redesigning each module's panels
(Dashboard/Home, Students, Fees, Attendance, Supply OS, Urgent Procurement, Superadmin/billing,
Zone, RBAC), any information-architecture changes to what a module *does*, backend/API changes,
and the fonts-bundling infra task (tracked as its own small task).

The rollout sequence (which module batch first, risk order) is produced by the implementation
plan via writing-plans, not fixed here.

---

## 8. Acceptance criteria

- [ ] All new/changed color tokens are authored in OKLCH; every text-on-surface pairing meets
      WCAG 2.2 AA (4.5:1 body, 3:1 large/UI); verified in light and dark.
- [ ] Chart palettes pass the dataviz validator in both modes (already verified: they do).
- [ ] Type: 14px default UI, 16px prose only, nothing <12px; tabular figures on all numeric
      columns; slashed-zero face in production.
- [ ] Spacing strictly on the 8pt scale; three persisted density modes (32/40/48).
- [ ] Depth via hairlines + surface steps; shadows only on floating layers; 2px `:focus-visible`
      ring ≥3:1 on every surface.
- [ ] Status and chart series never rely on color alone (icon/label/shape present).
- [ ] `prefers-reduced-motion` respected; theme toggle overrides `prefers-color-scheme` both ways.
- [ ] Legacy `--ck-*`/shorthand consumers render under the new palette with no markup change.
- [ ] `npm run build` passes with 0 TypeScript errors after the token layer lands.
```
