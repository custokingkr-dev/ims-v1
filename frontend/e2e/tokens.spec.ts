import { expect, test } from '@playwright/test';
import { signIn } from './support/session';

test('fonts load via a preconnected stylesheet link, not CSS @import', async ({ page }) => {
  await page.goto('/login');

  const preconnect = page.locator('link[rel="preconnect"][href="https://fonts.gstatic.com"]');
  await expect(preconnect).toHaveCount(1);

  const stylesheet = page.locator('link[rel="stylesheet"][href*="fonts.googleapis.com"]');
  await expect(stylesheet).toHaveCount(1);
});

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

test('body sets an accessible base size and line height', async ({ page }) => {
  await page.goto('/login');

  const base = await page.evaluate(() => {
    const body = getComputedStyle(document.body);
    return { fontSize: body.fontSize, lineHeight: body.lineHeight };
  });

  expect(base.fontSize).toBe('16px');
  expect(base.lineHeight).toBe('24px');
});

test('the .page-title heading actually renders in a served serif face, not the browser default', async ({
  page,
}) => {
  // getComputedStyle().fontFamily only echoes the CSS-declared font stack —
  // it reports "Instrument Serif", serif verbatim even when that face never
  // loaded and the browser silently substituted Times New Roman. That is
  // exactly the regression that reached prod (the @import that used to pull
  // Fraunces + Instrument Serif was replaced by a <link> requesting DM Sans
  // only), so a plain font-family assertion can't see it.
  //
  // document.fonts.check() is *also* unreliable here: empirically, Chromium
  // returns true for it even for a font family that was never declared
  // anywhere (e.g. "TotallyMadeUpFontXYZ") once .load() has been called on
  // it — so it can't distinguish "actually served" from "silently
  // substituted". What does discriminate reliably:
  //   1. document.fonts.load(...) resolves to an EMPTY array when no
  //      @font-face at all matches the family (nothing was ever requested
  //      for it in index.html) and a non-empty 'loaded' array when a
  //      @font-face was registered and successfully fetched.
  //   2. Rendering the same string on a <canvas> with the real font stack
  //      versus the bare generic ('serif') produces a DIFFERENT measured
  //      width only when the named face is actually in use; identical
  //      widths mean the browser fell back to its generic serif (Times New
  //      Roman on Windows).
  // Both were verified against a throwaway page with/without the Google
  // Fonts <link> before writing this assertion.
  await signIn(page);
  await page.goto('/schools');

  const heading = page.locator('.page-title').first();
  await expect(heading).toBeVisible();

  const result = await page.evaluate(async () => {
    const el = document.querySelector('.page-title') as HTMLElement;
    const family = getComputedStyle(el).fontFamily;

    await document.fonts.ready;
    const [instrumentFaces, frauncesFaces] = await Promise.all([
      document.fonts.load('400 42px "Instrument Serif"'),
      document.fonts.load('400 42px "Fraunces"'),
    ]);

    const measure = (fontFamily: string) => {
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d')!;
      ctx.font = `400 100px ${fontFamily}`;
      return ctx.measureText('MMMMMMMMWWWWWWWWiiiiiiiilllllllll').width;
    };

    return {
      family,
      instrumentSerifFaceCount: instrumentFaces.length,
      instrumentSerifStatus: instrumentFaces.map((f) => f.status),
      frauncesFaceCount: frauncesFaces.length,
      frauncesStatus: frauncesFaces.map((f) => f.status),
      pageTitleStackWidth: measure(family),
      genericSerifWidth: measure('serif'),
    };
  });

  // Sanity check: the CSS rule itself (styles.css, out of scope for this fix)
  // still names Instrument Serif.
  expect(result.family).toContain('Instrument Serif');

  // The actual regression check: the named faces must have been registered
  // and fetched via the <link> in index.html — an empty array here means
  // nothing on the page ever requested that family, which is exactly what
  // happens if the serif families are dropped from the font request again.
  expect(result.instrumentSerifFaceCount).toBeGreaterThan(0);
  expect(result.instrumentSerifStatus.every((s) => s === 'loaded')).toBe(true);
  expect(result.frauncesFaceCount).toBeGreaterThan(0);
  expect(result.frauncesStatus.every((s) => s === 'loaded')).toBe(true);

  // Belt and suspenders: the heading's real font stack must render visibly
  // differently from the bare generic fallback, proving Instrument Serif
  // (not Times New Roman) is what's actually painted.
  expect(Math.abs(result.pageTitleStackWidth - result.genericSerifWidth)).toBeGreaterThan(1);
});
