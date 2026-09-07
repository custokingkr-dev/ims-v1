# Frontend Design-Token Adoption and Visual Refresh

**Date:** 2026-09-06
**Status:** Approved (design); implementation plan not yet written
**Scope:** `frontend/` only. No backend, API, or contract changes.

## Problem

The frontend has three competing styling systems and no adopted design system.

Measured on 2026-09-06:

| Signal | Count |
|---|---|
| Legacy shorthand var usages (`--g`, `--ink`, `--bg`, ...) | **1,546** |
| `--ck-*` design-token usages outside `tokens.css` | **46** |
| `--ck-space-*` usages | **0** |
| Hardcoded hex in `.tsx` | **331** across 23 files |
| Hardcoded hex in `.css` | **425** |
| Hardcoded `px` font-sizes in `.css` | **532** across 33 distinct values |
| Those below 12px (excluding `/design-preview`) | **162** |
| Inline `style={{...}}` blocks | **998** |
| Distinct breakpoints | **11** (600/640/720/760/820/900/960/980/1100/1200) |
| `grid-template-columns` with fixed px | **65** |
| Tables vs `ck-table-wrap` | **46 vs 17** |

`tokens.css` (118 lines) was written and never adopted — 46 usages against 756 hardcoded
colours. The legacy shorthand vars in `styles.css` are the de-facto system at 1,546 usages.
A third system, `--dpx-*`, exists in `styles/design-preview.css` (780 lines) behind the
routed `/design-preview` page.

Two defects compound this:

1. **Fonts load via `@import`** at the top of `styles.css` — the slowest path (parse CSS,
   discover import, fetch font CSS, fetch fonts), causing flash-of-unstyled-text.
2. **The type scale is entirely `px`**, so browser font-size preferences are ignored. WCAG
   requires text to resize to 200% without loss of content or function.

## Goals

- One token system, adopted, with the legacy names surviving as aliases.
- A deliberate visual refresh: cool-neutral palette, single brand accent, readable type.
- Fix the `px` type scale, font loading, and breakpoint sprawl.
- Every slice independently reviewable and shippable.

## Non-goals

- No CSS framework migration. Tailwind was considered and rejected: 27k lines to convert,
  discards working CSS, and kills the `ck-` convention CONTRIBUTING mandates.
- No component-library introduction.
- Accessibility work beyond typography (label association, focus management, icon-button
  naming) is a **separate workstream**, already identified: 352 inputs vs 17 `htmlFor`.
- Responsive reflow beyond the breakpoint collapse is a **separate workstream**.

## Design direction

### Colour

Promote the already-designed `--dpx-*` direction into the real token system. This is the
team's own prior exploration, visible at `/design-preview`, and adopting it removes a
competing token set.

```
--ck-bg-app          #f4f6f8    (was #f7f6f3 warm cream)
--ck-bg-surface      #ffffff
--ck-text-primary    #1f2933    (was #1a1a1a pure black)
--ck-text-secondary  #667085
--ck-text-muted      #8b95a5
--ck-border-subtle   #e3e7ec
--ck-border-default  #d3d9e1
--ck-color-primary   #166b49    <- the only brand accent
```

Blue (`#2f68b2`), amber (`#a45b16`), red (`#b42318`), orange and purple survive **only as
status colours**, never as decoration or module identity. This is the near-monochrome plus
single-accent pattern that current practice favours.

### Typography

The `/design-preview` colour direction is adopted; its **type scale is explicitly rejected**.
That file uses 19 distinct sizes, most between 6.5px and 10px, including fractional values
(7.3px, 7.8px, 8.5px). That is unreadable and fails accessibility guidance.

New scale, rem-based against a 16px root, hard floor of 12px:

```
--ck-text-xs    0.75rem     12px   captions, labels only
--ck-text-sm    0.8125rem   13px
--ck-text-base  0.875rem    14px   dense table body
--ck-text-md    1rem        16px   default body
--ck-text-lg    1.125rem    18px
--ck-text-xl    1.375rem    22px
--ck-text-2xl   1.75rem     28px
--ck-text-3xl   2.25rem     36px
```

**Important asymmetry — colour and typography do not land together.**

The palette change propagates app-wide immediately, because 1,546 call sites already resolve
through CSS variables. Typography does **not**: there are **532 hardcoded `px` font-sizes in
CSS** across 33 distinct values (6.5px to 40px, including fractional 7.3/7.8/8.5/9.5/10.5/
11.5/12.5/13.5px), and they bypass the token scale entirely. There is also no global
`font-size` on `html`, `body`, or `:root` — the base is simply the browser default.

Excluding the `/design-preview` demo page, the real application has **423** hardcoded px
font-sizes, of which **162 are below 12px** (`styles.css` 98, `attendance.css` 33,
`photo-import.css` 15, `erp-modules.css` 14, `sidebar.css` 2).

Therefore slice 1 **defines** the type scale and sets a global baseline, but the visible
typography improvement only arrives in slices 2 and 3 as hardcoded sizes are replaced.
Slice 1 must not be described or reviewed as if it fixes typography.

- `line-height: 1.5` on body text.
- DM Sans throughout; serif display faces (Instrument Serif, Fraunces) are dropped, matching
  the preview direction.
- `font-variant-numeric: tabular-nums` on all numeric table cells.
- Font loading moves from CSS `@import` to `<link rel="preconnect">` plus
  `<link rel="stylesheet">` in `frontend/index.html`.

### Breakpoints

Eleven ad-hoc breakpoints collapse to four:

```
640px   small tablet / large phone
768px   tablet
1024px  laptop
1280px  desktop
```

These remain **literal values in media queries**, because CSS custom properties are invalid
inside media query conditions and the project has no PostCSS, Sass, or stylelint. They are
documented as constants in `tokens.css` comments and enforced by review, not tooling.
Adding PostCSS `postcss-custom-media` was considered and deferred: it is a build dependency
whose only benefit here is authoring ergonomics.

## Architecture: invert the dependency graph

Today `tokens.css` aliases the legacy vars, so the token layer depends on the legacy layer:

```css
--ck-color-primary: var(--g);   /* token depends on legacy */
```

Invert it. Real values live in `tokens.css`; legacy names become aliases:

```css
/* tokens.css - source of truth */
--ck-text-primary: #1f2933;
--ck-color-primary: #166b49;

/* styles.css - compatibility aliases */
--ink: var(--ck-text-primary);
--g:   var(--ck-color-primary);
```

A single change of roughly 25 lines repaints **1,546 existing call sites**. The visual
refresh lands across the whole application without editing any panel. The legacy aliases
stay indefinitely; removing them is not a goal and would be churn for its own sake.

**Import order does not need to change.** `main.tsx` imports `styles.css` before
`tokens.css`, but CSS custom properties resolve at computed-value time, not parse time, so
`--ink: var(--ck-text-primary)` works regardless of which file is parsed first, provided both
apply to the same element (`:root`). Order matters only when the *same* property name is
defined twice; these two files define disjoint names. No change required.

## Slices

Each slice is a separate pull request against `dev`, independently shippable.

| # | Slice | Scope | Acceptance |
|---|---|---|---|
| 1 | Foundation | Invert graph, new palette, rem type scale, font `<link>`, screenshot baseline | App repaints; all 35 unit test files green; before/after contact sheet produced |
| 2 | CSS hex + px font-sizes to tokens | 425 hex + 423 px font-sizes across 8 stylesheets | Zero hardcoded hex in `src/**/*.css` except `tokens.css`; no font-size below `--ck-text-xs` |
| 3 | TSX hex plus static inline styles | 331 sites, 23 files - **split per panel, not one PR** | Zero hardcoded hex in `.tsx`; genuinely dynamic inline styles retained |
| 4 | Retire `--dpx-*` | 780-line `design-preview.css`, repoint or delete `/design-preview` | Only one token system remains |
| 5 | Breakpoint collapse | 42 media queries to 4 breakpoints | No breakpoint outside the four constants |
| 6 | Tabular numerals | Numeric cells across 46 tables | Numeric columns align on the decimal |

Slice 1 carries the entire **colour** change. Slices 2 to 6 remove outliers that would
otherwise clash with the new palette, and slices 2 and 3 carry the **typography** change,
since 532 hardcoded px font-sizes bypass the token scale and cannot be fixed by editing
`tokens.css` alone.

## Verification

Current safety net: 35 vitest files (behaviour, not appearance), **one** Playwright e2e spec
(`auth.spec.ts`), and **zero** screenshot assertions. Nothing today would catch a broken
layout across 40-plus panels.

Slice 1 therefore adds a Playwright screenshot pass over the main panels, captured **before**
any styling change as a baseline. These are **not** pass/fail regression assertions, because
the visual change is intentional. They exist so every slice produces a before/after contact
sheet that a human can review panel by panel.

This is the mitigation for accepted risk 1 below: the diff cannot distinguish a mechanical
change from a design decision, but the screenshots can.

Per slice: unit tests green, `npm run build` clean (zero TypeScript errors), screenshots
reviewed.

## Risks and accepted trade-offs

1. **Tokenisation and visual refresh are combined.** Recommended against and overruled by the
   product owner, who chose to combine them. Consequence: a mechanical replacement and an
   intentional design change look identical in review. Mitigated by the screenshot contact
   sheets, and by keeping the mechanical slices (2, 3) separate from the design slice (1).
2. **Slice 3 is large** at 331 sites across 23 files. Split per panel; do not attempt one PR.
3. **Serif display faces are dropped**, removing a distinctive brand element. Reversible by
   restoring `--ck-font-display` on page titles only.
4. **The cool-neutral shift is visible on every screen.** This is the requested bolder change,
   but it means no screen is left untouched.
5. **`/design-preview` is routed in production.** Whether it should be is a separate decision
   from this work.

## Open decisions for the product owner

- Keep one serif for page titles, or go fully sans?
- Should `/design-preview` remain routed in production after slice 4?
