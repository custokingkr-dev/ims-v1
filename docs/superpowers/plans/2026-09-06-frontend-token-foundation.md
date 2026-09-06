# Frontend Token Foundation (Slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Invert the CSS custom-property dependency graph so `tokens.css` holds the real values, repainting all 1,546 existing variable call sites with the new cool-neutral palette in one change.

**Architecture:** Today `tokens.css` aliases the legacy shorthand vars (`--ck-color-primary: var(--g)`). We flip that: literal values move into `tokens.css`, and the legacy names in `styles.css` become aliases pointing at tokens. Because 1,546 call sites already resolve through `var(--g)`, `var(--ink)` and friends, the whole application repaints without editing a single panel. Font loading also moves off CSS `@import` onto `<link>` tags in `index.html`.

**Tech Stack:** React 18, TypeScript, Vite 5, plain CSS (no PostCSS/Sass/Tailwind), Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-06-frontend-token-adoption-design.md`

## Global Constraints

- All CSS class names use the `ck-` prefix (CONTRIBUTING requirement).
- No hard-coded colours in new code — use `:root` CSS variables.
- Breakpoints are limited to four literal values: **640px, 768px, 1024px, 1280px**.
- Type scale is rem-based against a 16px root, with a hard floor of `--ck-text-xs` (0.75rem / 12px).
- `--ck-color-primary: #166b49` is the only brand accent. Blue/amber/red/orange/purple are status colours only.
- Do **not** delete the legacy shorthand vars (`--g`, `--ink`, `--bg`, …). They stay as aliases indefinitely.
- Do **not** change `main.tsx` import order — CSS custom properties resolve at computed-value time, so it is irrelevant.
- Slice 1 does **not** fix typography app-wide. 532 hardcoded px font-sizes bypass the token scale; those are slices 2 and 3.
- Every task ends green: `npm test` passes and `npm run build` emits zero TypeScript errors.

## File Structure

| File | Responsibility |
|---|---|
| `frontend/e2e/support/session.ts` | **Create.** Reusable Playwright helpers: API mocking and authenticated session setup, extracted so the visual spec does not duplicate `auth.spec.ts`. |
| `frontend/e2e/visual.spec.ts` | **Create.** Captures full-page screenshots of all five routes into a labelled folder. Not pass/fail — it produces review artefacts. |
| `frontend/e2e/tokens.spec.ts` | **Create.** Real assertions on computed styles: palette values, font link, type scale, line-height. This is the regression guard. |
| `frontend/package.json` | **Modify.** Add `test:visual` script. |
| `frontend/index.html` | **Modify.** Add font `<link rel="preconnect">` + `<link rel="stylesheet">`. |
| `frontend/src/styles.css` | **Modify.** Remove `@import`; convert `:root` legacy vars to token aliases; add body base type rules. |
| `frontend/src/styles/tokens.css` | **Modify.** Becomes the source of truth: literal colour values, rem type scale, breakpoint documentation. |

`auth.spec.ts` is deliberately **not** modified — it works, and touching it adds risk for no gain.

---

### Task 1: Visual baseline harness

Captures "before" screenshots. This must land **before** any styling change, or there is nothing to compare against.

**Files:**
- Create: `frontend/e2e/support/session.ts`
- Create: `frontend/e2e/visual.spec.ts`
- Modify: `frontend/package.json`

**Interfaces:**
- Produces: `mockAuthenticatedApi(page: Page): Promise<void>` and `signIn(page: Page): Promise<void>`, exported from `e2e/support/session.ts` and consumed by `visual.spec.ts` in this task. `tokens.spec.ts` (Tasks 2–5) does **not** use them — every token assertion runs against the unauthenticated `/login` route, which needs no session.

- [ ] **Step 1: Create the shared session helper**

Create `frontend/e2e/support/session.ts`:

```typescript
import type { Page, Route } from '@playwright/test';

export const user = {
  accessToken: 'e2e-access-token',
  userId: 42,
  fullName: 'Asha Admin',
  email: 'asha@example.test',
  role: 'ADMIN',
  branchId: 7,
  branchName: 'Green Valley School',
  permissions: [],
};

export const workspace = {
  school: { name: 'Green Valley School', meta: '2026-27', timeZone: 'Asia/Kolkata' },
  dashboard: {},
  orders: [],
  staff: [],
};

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

export async function mockAuthenticatedApi(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/api/v1/auth/login') return json(route, user);
    if (pathname === '/api/v1/auth/refresh') return json(route, user);
    if (pathname === '/api/v1/workspace') return json(route, workspace);
    return json(route, {});
  });
}

export async function signIn(page: Page) {
  await mockAuthenticatedApi(page);
  await page.addInitScript(() => localStorage.setItem('custoking_isLoggedIn', 'true'));
}
```

- [ ] **Step 2: Create the visual capture spec**

Create `frontend/e2e/visual.spec.ts`:

```typescript
import { test } from '@playwright/test';
import { signIn } from './support/session';

const LABEL = process.env.VISUAL_LABEL ?? 'before';

const publicRoutes = [
  { name: 'login', path: '/login' },
  { name: 'design-preview', path: '/design-preview' },
];

const protectedRoutes = [
  { name: 'dashboard', path: '/dashboard' },
  { name: 'schools', path: '/schools' },
  { name: 'zones', path: '/zones' },
];

for (const route of publicRoutes) {
  test(`capture ${route.name}`, async ({ page }) => {
    await page.goto(route.path);
    await page.waitForLoadState('networkidle');
    await page.screenshot({
      path: `test-results/visual/${LABEL}/${route.name}.png`,
      fullPage: true,
    });
  });
}

for (const route of protectedRoutes) {
  test(`capture ${route.name}`, async ({ page }) => {
    await signIn(page);
    await page.goto(route.path);
    await page.waitForLoadState('networkidle');
    await page.screenshot({
      path: `test-results/visual/${LABEL}/${route.name}.png`,
      fullPage: true,
    });
  });
}
```

- [ ] **Step 3: Add the npm script**

In `frontend/package.json`, add to `"scripts"` after `"test:e2e"`:

```json
"test:visual": "playwright test visual.spec.ts"
```

- [ ] **Step 4: Capture the BEFORE baseline**

Run from `frontend/`:

```bash
VISUAL_LABEL=before npm run test:visual
```

Expected: 5 tests pass; 5 PNGs written to `test-results/visual/before/`.
Verify with `ls test-results/visual/before/` — expect `login.png`, `design-preview.png`, `dashboard.png`, `schools.png`, `zones.png`.

If a protected route renders an error shell rather than content, that is acceptable for a baseline — it will render identically in the "after" pass. Do **not** expand API mocking to chase a perfect render; that is scope creep.

- [ ] **Step 5: Commit**

```bash
git add frontend/e2e/support/session.ts frontend/e2e/visual.spec.ts frontend/package.json
git commit -m "test(visual): add screenshot capture harness for styling review"
```

Note: `test-results/` is build output — do not commit the PNGs. Confirm it is gitignored with `git check-ignore -v frontend/test-results`; if it is not ignored, add `frontend/test-results/` to `.gitignore` as part of this commit.

---

### Task 2: Move font loading off CSS `@import`

**Files:**
- Create: `frontend/e2e/tokens.spec.ts`
- Modify: `frontend/index.html`
- Modify: `frontend/src/styles.css:1`

**Interfaces:**
- Consumes: nothing.
- Produces: `frontend/e2e/tokens.spec.ts`, extended by Tasks 3, 4 and 5.

- [ ] **Step 1: Write the failing test**

Create `frontend/e2e/tokens.spec.ts`:

```typescript
import { expect, test } from '@playwright/test';

test('fonts load via a preconnected stylesheet link, not CSS @import', async ({ page }) => {
  await page.goto('/login');

  const preconnect = page.locator('link[rel="preconnect"][href="https://fonts.gstatic.com"]');
  await expect(preconnect).toHaveCount(1);

  const stylesheet = page.locator('link[rel="stylesheet"][href*="fonts.googleapis.com"]');
  await expect(stylesheet).toHaveCount(1);
});
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
npx playwright test tokens.spec.ts
```

Expected: FAIL — `Expected: 1, Received: 0`, because `index.html` has no font links today.

- [ ] **Step 3: Add the links to `index.html`**

In `frontend/index.html`, insert after the `<meta name="viewport" …>` line and before `<title>`:

```html
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&display=swap"
    />
```

Note the serif families (`Fraunces`, `Instrument Serif`) are intentionally dropped per the spec — DM Sans only.

- [ ] **Step 4: Remove the `@import` from `styles.css`**

Delete line 1 of `frontend/src/styles.css` entirely — the `@import url('https://fonts.googleapis.com/css2?…')` line. The file must now begin with the blank line preceding `:root {`.

- [ ] **Step 5: Run tests to verify they pass**

```bash
npx playwright test tokens.spec.ts
npm test
npm run build
```

Expected: tokens.spec.ts PASSES; all 35 vitest files pass; build emits zero TypeScript errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/index.html frontend/src/styles.css frontend/e2e/tokens.spec.ts
git commit -m "perf(frontend): load fonts via link tags instead of CSS @import"
```

---

### Task 3: Invert the dependency graph and apply the new palette

This is the task that repaints the application.

**Files:**
- Modify: `frontend/src/styles/tokens.css:6-50`
- Modify: `frontend/src/styles.css:3-8`
- Modify: `frontend/e2e/tokens.spec.ts`

**Interfaces:**
- Consumes: `frontend/e2e/tokens.spec.ts` from Task 2.
- Produces: `--ck-*` colour tokens as the source of truth; legacy vars as aliases.

- [ ] **Step 1: Write the failing test**

Append to `frontend/e2e/tokens.spec.ts`:

```typescript
test('the new cool-neutral palette is applied app-wide', async ({ page }) => {
  await page.goto('/login');

  const bodyBackground = await page.evaluate(
    () => getComputedStyle(document.body).backgroundColor,
  );
  expect(bodyBackground).toBe('rgb(244, 246, 248)');

  const bodyColor = await page.evaluate(() => getComputedStyle(document.body).color);
  expect(bodyColor).toBe('rgb(31, 41, 51)');
});

test('legacy shorthand vars resolve through the token layer', async ({ page }) => {
  await page.goto('/login');

  const resolved = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    return {
      legacyGreen: root.getPropertyValue('--g').trim(),
      tokenGreen: root.getPropertyValue('--ck-color-primary').trim(),
    };
  });

  expect(resolved.tokenGreen).toBe('#166b49');
  expect(resolved.legacyGreen).toBe('#166b49');
});
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
npx playwright test tokens.spec.ts
```

Expected: FAIL — background is `rgb(247, 246, 243)` (the old warm cream) and `--g` is `#1a6840`.

- [ ] **Step 3: Make `tokens.css` the source of truth**

In `frontend/src/styles/tokens.css`, replace the Brand Colors, Surfaces, Text and Borders blocks (currently lines 7–45, each `var(--x)` alias) with literal values:

```css
  /* ── Brand Colors ──────────────────────────────────────────────── */
  --ck-color-primary:        #166b49;
  --ck-color-primary-dark:   #0f5539;
  --ck-color-primary-soft:   #e8f4ee;
  --ck-color-primary-border: #c4ddd0;

  /* Status only — never decoration or module identity */
  --ck-color-accent:         #2f68b2;
  --ck-color-accent-soft:    #e6edf8;
  --ck-color-warning:        #a45b16;
  --ck-color-warning-soft:   #fff3e6;
  --ck-color-danger:         #b42318;
  --ck-color-danger-soft:    #fdecea;
  --ck-color-orange:         #d14e12;
  --ck-color-orange-soft:    #fff0e8;
  --ck-color-purple:         #5b2d8a;
  --ck-color-purple-soft:    #f2edf9;

  /* ── Surfaces ──────────────────────────────────────────────────── */
  --ck-bg-app:               #f4f6f8;
  --ck-bg-surface:           #ffffff;
  --ck-bg-surface-raised:    #fbfcfc;
  --ck-bg-overlay:           rgba(0,0,0,.32);

  /* ── Text ──────────────────────────────────────────────────────── */
  --ck-text-primary:         #1f2933;
  --ck-text-secondary:       #667085;
  --ck-text-muted:           #8b95a5;
  --ck-text-inverse:         #ffffff;

  /* ── Borders ───────────────────────────────────────────────────── */
  --ck-border-subtle:        #e3e7ec;
  --ck-border-default:       #d3d9e1;
```

Leave the Shadows, Radius, Spacing, Typography, Z-Index, Animation, Status and Chart blocks untouched in this step — but note the Status and Chart blocks reference `var(--g)`, `var(--am)` etc. Those still resolve correctly once Step 4 makes the legacy vars aliases, so no change is needed there.

- [ ] **Step 4: Convert the legacy vars in `styles.css` to aliases**

In `frontend/src/styles.css`, replace the whole `:root { … }` block (lines 3–8, the four dense lines of shorthand vars) with:

```css
:root {
  /* Compatibility aliases. Real values live in styles/tokens.css.
     1,546 existing call sites resolve through these names. Do not delete. */
  --bg:      var(--ck-bg-app);
  --white:   var(--ck-bg-surface);
  --ink:     var(--ck-text-primary);
  --ink2:    var(--ck-text-secondary);
  --ink3:    var(--ck-text-muted);
  --border:  var(--ck-border-subtle);
  --border2: var(--ck-border-default);
  --g:       var(--ck-color-primary);
  --g1:      var(--ck-color-primary-soft);
  --g2:      var(--ck-color-primary-border);
  --b:       var(--ck-color-accent);
  --b1:      var(--ck-color-accent-soft);
  --am:      var(--ck-color-warning);
  --am1:     var(--ck-color-warning-soft);
  --re:      var(--ck-color-danger);
  --re1:     var(--ck-color-danger-soft);
  --or:      var(--ck-color-orange);
  --or1:     var(--ck-color-orange-soft);
  --pu:      var(--ck-color-purple);
  --pu1:     var(--ck-color-purple-soft);
  --shadow:  var(--ck-shadow-raised);
}
```

`--ck-shadow-raised` currently reads `var(--shadow)` in `tokens.css`, which would now be circular. Change that one line in `tokens.css` to the literal value:

```css
  --ck-shadow-raised:        0 12px 34px rgba(0,0,0,.06),0 3px 10px rgba(0,0,0,.04);
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
npx playwright test tokens.spec.ts
npm test
npm run build
```

Expected: all tokens.spec.ts tests PASS; 35 vitest files pass; build clean.

If any variable renders as transparent or black, a circular reference remains — search `tokens.css` for a `var(--x)` whose `--x` is now itself an alias, and replace it with a literal.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/styles/tokens.css frontend/src/styles.css frontend/e2e/tokens.spec.ts
git commit -m "feat(frontend): invert token graph and apply cool-neutral palette"
```

---

### Task 4: Define the rem type scale

**Files:**
- Modify: `frontend/src/styles/tokens.css` (Typography Scale block)
- Modify: `frontend/e2e/tokens.spec.ts`

**Interfaces:**
- Consumes: `tokens.spec.ts` from Task 3.
- Produces: rem-valued `--ck-text-*` tokens consumed by slices 2 and 3.

- [ ] **Step 1: Write the failing test**

Append to `frontend/e2e/tokens.spec.ts`:

```typescript
test('the type scale is rem-based and floored at 12px', async ({ page }) => {
  await page.goto('/login');

  const scale = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    return {
      xs: root.getPropertyValue('--ck-text-xs').trim(),
      base: root.getPropertyValue('--ck-text-base').trim(),
      md: root.getPropertyValue('--ck-text-md').trim(),
    };
  });

  expect(scale.xs).toBe('0.75rem');
  expect(scale.base).toBe('0.875rem');
  expect(scale.md).toBe('1rem');
});
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
npx playwright test tokens.spec.ts
```

Expected: FAIL — values are `10px`, `13px`, `14px`.

- [ ] **Step 3: Replace the Typography Scale block**

In `frontend/src/styles/tokens.css`, replace the eight `--ck-text-*` size declarations with:

```css
  /* rem against a 16px root. 0.75rem (12px) is a hard floor — nothing smaller. */
  --ck-text-xs:              0.75rem;    /* 12px — captions, labels only */
  --ck-text-sm:              0.8125rem;  /* 13px */
  --ck-text-base:            0.875rem;   /* 14px — dense table body */
  --ck-text-md:              1rem;       /* 16px — default body */
  --ck-text-lg:              1.125rem;   /* 18px */
  --ck-text-xl:              1.375rem;   /* 22px */
  --ck-text-2xl:             1.75rem;    /* 28px */
  --ck-text-3xl:             2.25rem;    /* 36px */
```

Also replace the font-family declarations in the same file, dropping the serif faces:

```css
  --ck-font-display:         'DM Sans', system-ui, sans-serif;
  --ck-font-heading:         'DM Sans', system-ui, sans-serif;
  --ck-font-body:            'DM Sans', system-ui, sans-serif;
  --ck-font-mono:            ui-monospace, 'JetBrains Mono', monospace;
```

- [ ] **Step 4: Document the breakpoint constants**

Append to the end of the `:root` block in `frontend/src/styles/tokens.css`:

```css
  /* ── Breakpoints (documentation only) ──────────────────────────────
     CSS custom properties are INVALID inside media query conditions, so
     these cannot be used as var(). Use these four literals and no others:
       640px   small tablet / large phone
       768px   tablet
       1024px  laptop
       1280px  desktop
     Enforced by review, not tooling. See slice 5. */
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
npx playwright test tokens.spec.ts
npm test
npm run build
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/styles/tokens.css frontend/e2e/tokens.spec.ts
git commit -m "feat(frontend): rem-based type scale floored at 12px"
```

---

### Task 5: Body typography baseline

Kept separate from Task 3 so it can be reverted alone. `line-height: 1.5` inherits into nearly everything — only 64 explicit `line-height` declarations exist in the entire codebase — so this grows every line box by roughly 25% and is the single most likely source of layout shift in this slice.

**Files:**
- Modify: `frontend/src/styles.css` (the `body` rule, currently line 11)
- Modify: `frontend/e2e/tokens.spec.ts`

**Interfaces:**
- Consumes: `--ck-text-md`, `--ck-font-body` from Task 4.

- [ ] **Step 1: Write the failing test**

Append to `frontend/e2e/tokens.spec.ts`:

```typescript
test('body sets an accessible base size and line height', async ({ page }) => {
  await page.goto('/login');

  const base = await page.evaluate(() => {
    const body = getComputedStyle(document.body);
    return { fontSize: body.fontSize, lineHeight: body.lineHeight };
  });

  expect(base.fontSize).toBe('16px');
  expect(base.lineHeight).toBe('24px');
});
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
npx playwright test tokens.spec.ts
```

Expected: FAIL on `lineHeight` — it is currently `normal`, not `24px`.

- [ ] **Step 3: Update the body rule**

In `frontend/src/styles.css`, replace the `body { … }` rule with:

```css
body {
  margin: 0;
  font-family: var(--ck-font-body);
  font-size: var(--ck-text-md);
  line-height: 1.5;
  background: var(--bg);
  color: var(--ink);
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
npx playwright test tokens.spec.ts
npm test
npm run build
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/styles.css frontend/e2e/tokens.spec.ts
git commit -m "feat(frontend): accessible body type baseline with 1.5 line height"
```

---

### Task 6: Capture the after contact sheet and review

**Files:** none modified — this task produces review artefacts.

- [ ] **Step 1: Capture the AFTER screenshots**

```bash
VISUAL_LABEL=after npm run test:visual
```

Expected: 5 tests pass; 5 PNGs in `test-results/visual/after/`.

- [ ] **Step 2: Compare before and after**

Open each pair side by side:

```
test-results/visual/before/login.png          test-results/visual/after/login.png
test-results/visual/before/dashboard.png      test-results/visual/after/dashboard.png
test-results/visual/before/schools.png        test-results/visual/after/schools.png
test-results/visual/before/zones.png          test-results/visual/after/zones.png
test-results/visual/before/design-preview.png test-results/visual/after/design-preview.png
```

**Expected differences:** warm cream backgrounds become cool grey; near-black text becomes blue-black; green shifts slightly; vertical rhythm loosens from the 1.5 line-height.

**Report as defects:** overlapping text, clipped content, unreadable contrast, broken alignment, any element that has lost its background or border entirely (which indicates an unresolved circular variable reference).

`design-preview.png` should be **almost unchanged**, because it is scoped to `--dpx-*`. A large diff there means a `--dpx-*` variable was leaking to a legacy name and needs investigating.

- [ ] **Step 3: Full verification**

```bash
npm test
npm run build
npx playwright test
```

Expected: 35 vitest files pass; build clean; all e2e specs pass including the untouched `auth.spec.ts`.

- [ ] **Step 4: Open the pull request**

```bash
git push -u origin codex/frontend-token-adoption
gh pr create --base dev --title "Frontend token foundation: invert the graph and apply the new palette"
```

Attach the before/after pairs to the PR description. State plainly that typography has **not** changed app-wide and that this is expected — 532 hardcoded px font-sizes are slices 2 and 3.

---

## Rollback

Every task is a separate commit. Task 5 (line-height) is the most likely to need reverting and can be dropped with `git revert` without disturbing the palette. Task 3 is the palette itself; reverting it restores the warm cream values while leaving the font-loading fix intact.
