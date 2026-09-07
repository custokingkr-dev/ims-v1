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
 *
 * design-preview.css is excluded: it is scoped to its own --dpx-* system and is
 * retired separately, so converting it now risks being discarded work.
 */
const DRIFTED_COLOURS = [
  '#1a6840', '#155c36', '#145234', '#135533',
  '#fafcff', '#f8faf9', '#fffaf3', '#fafcfb', '#fff6f6', '#fcfaf7', '#fafafa', '#f7fbff',
  '#f7f4ef', '#f7f4ee', '#f7f4ed', '#f6f4ef', '#f5f1ea', '#f4f2ec', '#f3f2ef', '#f4f4f0',
  '#f7f5ff', '#f0f8f4', '#e9eeeb', '#e8f5ee', '#8d8d8d',
];

const EXCLUDED = ['design-preview.css', 'tokens.css'];

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
