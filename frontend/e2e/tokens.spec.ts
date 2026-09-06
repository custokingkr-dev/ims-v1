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
