import { expect, test } from '@playwright/test';

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
