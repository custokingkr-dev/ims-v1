import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';

/**
 * Source-level guard, not a browser test. It lives here rather than under src/
 * because tsconfig only type-checks src/ and the project has no @types/node,
 * and because the CSSOM cannot be used for this: browsers normalise #fafcff to
 * rgb(250, 252, 255) in cssText, so a runtime hex search silently misses most
 * matches.
 *
 * These colours predate the design-token system and duplicate the value of a
 * token that now exists. The four greens are the old brand green and its
 * shades; the rest are near-white and warm-grey surfaces.
 */
const DRIFTED_COLOURS = [
  '#1a6840', '#155c36', '#145234', '#135533',
  '#fafcff', '#f8faf9', '#fffaf3', '#fafcfb', '#fff6f6', '#fcfaf7', '#fafafa', '#f7fbff',
  '#f7f4ef', '#f7f4ee', '#f7f4ed', '#f6f4ef', '#f5f1ea', '#f4f2ec', '#f3f2ef', '#f4f4f0',
  '#f7f5ff', '#f0f8f4', '#e9eeeb', '#e8f5ee', '#8d8d8d',
];

const EXCLUDED = ['tokens.css'];

function stylesheetPaths(): string[] {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
  const found: string[] = [join(root, 'styles.css')];
  for (const name of readdirSync(join(root, 'styles'))) {
    if (name.endsWith('.css')) found.push(join(root, 'styles', name));
  }
  return found.filter((p) => !EXCLUDED.some((e) => p.endsWith(e)));
}

test('no CSS colour duplicates the value of an existing design token', () => {
  const offenders: string[] = [];

  for (const path of stylesheetPaths()) {
    const css = readFileSync(path, 'utf8').toLowerCase();
    for (const colour of DRIFTED_COLOURS) {
      if (css.includes(colour)) {
        offenders.push(`${path.split(/[\/]/).pop()}: ${colour}`);
      }
    }
  }

  expect(offenders).toEqual([]);
});

/**
 * #fff is the largest remaining cluster and the most dangerous to convert
 * mechanically: it matches BOTH --ck-bg-surface and --ck-text-inverse by value,
 * because both are #ffffff. Replacing it by value alone would point backgrounds
 * at a text token - identical rendering today, wrong semantics, and a defect the
 * moment either token is changed independently. So this guard is role-aware.
 */
test('white is not hardcoded where a role-appropriate token exists', () => {
  const offenders: string[] = [];

  for (const path of stylesheetPaths()) {
    const css = readFileSync(path, 'utf8');
    const file = path.split(/[\/]/).pop();

    css.split(/\r?\n/).forEach((line, index) => {
      const declarations = line.match(/(background|color)\s*:\s*[^;{}]*/gi) ?? [];
      for (const declaration of declarations) {
        if (/#(fff|ffffff)\b/i.test(declaration)) {
          offenders.push(`${file}:${index + 1} ${declaration.trim().slice(0, 60)}`);
        }
      }
    });
  }

  expect(offenders).toEqual([]);
});

/**
 * Status-tint borders. Unlike the drift above these were never a token's value -
 * they are a real palette the token set simply lacked a name for. Each cluster is
 * one intended colour that accumulated near-identical variants: six pinks within a
 * few units of each other, six tans likewise. Collapsing each cluster onto a single
 * minted token names the colour that was already there.
 *
 * --ck-color-primary-border already existed for green; danger, warning and purple
 * now have the equivalent.
 */
const UNNAMED_TINTS = [
  // danger — 19 uses, every one a border, outline or border-color
  '#f5c0bd', '#f4bbb8', '#f5c0bc', '#efc7c4', '#f3c1bd', '#f0c5c0', '#dca9a5',
  // warning — 10 uses, same
  '#f5d5a0', '#f5c090', '#efd9a3', '#eccda9', '#f5c878', '#f2d19d',
  // purple — 2 uses, both borders
  '#c5b0e8',
];

test('status-tint borders resolve through a named token', () => {
  const offenders: string[] = [];

  for (const path of stylesheetPaths()) {
    const css = readFileSync(path, 'utf8').toLowerCase();
    for (const colour of UNNAMED_TINTS) {
      if (css.includes(colour)) {
        offenders.push(`${path.split(/[\/]/).pop()}: ${colour}`);
      }
    }
  }

  expect(offenders).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Left-nav conformance.
 *
 * The colour guards above never looked at type, radius, motion or layer, so the
 * nav could (and did) sit on 10px/11px font-sizes, hand-rolled 6px/10px radii,
 * `.15s ease` and a bare `z-index: 40` while the rest of the app moved onto the
 * token scale. These guards close that hole at the SOURCE level for the two
 * files that own the nav. Deliberately not CSSOM-based: browsers normalise
 * values in cssText (hex -> rgb(), and the `font:` shorthand is re-serialised),
 * so a runtime check misses most of this.
 *
 * Scope is the nav-owned selector families, not whole files — styles.css is a
 * 2k-line legacy sheet and this guard is not a licence to fail on unrelated
 * rules. sidebar.css is nav-only, so all of it is in scope.
 * ──────────────────────────────────────────────────────────────────────────── */

const NAV_SELECTOR_PREFIXES = [
  '.ck-sidebar',
  '.ck-nav',
  '.ck-sb-',
  '.ck-user-',
  '.ck-school-',
  '.ck-menu-toggle',
];

type Rule = { file: string; selector: string; body: string };

/** Rules owned by the left nav, across styles.css and styles/sidebar.css. */
function navRules(): Rule[] {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
  const files = [join(root, 'styles.css'), join(root, 'styles', 'sidebar.css')];
  const rules: Rule[] = [];

  for (const path of files) {
    const file = path.split(/[\/\\]/).pop()!;
    // Strip comments first so commented-out values never trip a guard.
    const css = readFileSync(path, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    // Innermost declaration blocks only. An `@media` prelude can never form a
    // complete match (its body contains braces), so nested rules are picked up
    // individually and at-rule preludes are skipped.
    for (const [, selector, body] of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const sel = selector.trim();
      const isNav =
        file === 'sidebar.css' ||
        NAV_SELECTOR_PREFIXES.some((p) => sel.includes(p));
      if (isNav && sel && !sel.startsWith('@')) rules.push({ file, selector: sel, body });
    }
  }
  return rules;
}

function declarations(body: string): string[] {
  return body
    .split(';')
    .map((d) => d.trim())
    .filter(Boolean);
}

test('nav type sits on the rem scale, never on px', () => {
  const offenders: string[] = [];

  for (const { file, selector, body } of navRules()) {
    for (const declaration of declarations(body)) {
      const [property] = declaration.split(':');
      const name = property.trim().toLowerCase();
      // Both the longhand and the `font:` shorthand can smuggle in a px size.
      if (name !== 'font-size' && name !== 'font') continue;
      if (/\b\d*\.?\d+px\b/.test(declaration)) {
        offenders.push(`${file} ${selector.slice(0, 48)} -> ${declaration}`);
      }
    }
  }

  expect(offenders).toEqual([]);
});

test('nav radius, motion, elevation, scrim and layer resolve through tokens', () => {
  const offenders: string[] = [];

  const checks: Array<{ properties: string[]; pattern: RegExp; why: string }> = [
    // 6px/10px/12px radii predate --ck-radius-*.
    { properties: ['border-radius'], pattern: /\b\d*\.?\d+px\b/, why: 'use --ck-radius-*' },
    // `.18s ease` / `.12s ease` / `.15s ease` predate --ck-duration-* / --ck-ease-*.
    {
      properties: ['transition', 'transition-duration', 'animation', 'animation-duration'],
      pattern: /(^|[\s,(])\.?\d*\.?\d+m?s\b/,
      why: 'use --ck-duration-*',
    },
    // A bare integer layer bypasses the --ck-z-* ordering.
    { properties: ['z-index'], pattern: /^\s*-?\d+\s*$/, why: 'use --ck-z-*' },
    // Raw colour in elevation/scrim/fill positions.
    {
      properties: ['box-shadow', 'background', 'background-color', 'color', 'border-color'],
      pattern: /rgba?\(|#[0-9a-f]{3,8}\b/i,
      why: 'use a --ck-shadow-* / --ck-bg-* / colour token',
    },
  ];

  for (const { file, selector, body } of navRules()) {
    for (const declaration of declarations(body)) {
      const colon = declaration.indexOf(':');
      if (colon < 0) continue;
      const name = declaration.slice(0, colon).trim().toLowerCase();
      const value = declaration.slice(colon + 1);
      for (const check of checks) {
        if (!check.properties.includes(name)) continue;
        // `transition: none` and keyword-only values are fine.
        if (check.pattern.test(value)) {
          offenders.push(`${file} ${selector.slice(0, 40)} -> ${name}:${value.trim()} (${check.why})`);
        }
      }
    }
  }

  expect(offenders).toEqual([]);
});

/**
 * The specific bug this pair of tokens exists to prevent: .ck-main's margin-left
 * has to equal .ck-sidebar's width in BOTH the collapsed and the pinned state.
 * When those were four separate literals (64px twice, 248px twice) nothing
 * stopped one of them being edited alone.
 */
test('sidebar rail widths cannot drift from the .ck-main gutter', () => {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
  const tokens = readFileSync(join(root, 'styles', 'tokens.css'), 'utf8');

  for (const [name, value] of [
    ['--ck-sidebar-rail', '64px'],
    ['--ck-sidebar-expanded', '248px'],
    ['--ck-sidebar-drawer', '272px'],
  ]) {
    expect(tokens).toMatch(new RegExp(`${name}:\\s*${value}\\s*;`));
  }

  const offenders: string[] = [];
  for (const { file, selector, body } of navRules()) {
    for (const declaration of declarations(body)) {
      const colon = declaration.indexOf(':');
      if (colon < 0) continue;
      const name = declaration.slice(0, colon).trim().toLowerCase();
      if (name !== 'width' && name !== 'margin-left') continue;
      if (/\b(64|248|272)px\b/.test(declaration.slice(colon + 1))) {
        offenders.push(`${file} ${selector.slice(0, 40)} -> ${declaration}`);
      }
    }
  }

  expect(offenders).toEqual([]);
});
