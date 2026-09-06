import { expect, test } from '@playwright/test';

test('fonts load via a preconnected stylesheet link, not CSS @import', async ({ page }) => {
  await page.goto('/login');

  const preconnect = page.locator('link[rel="preconnect"][href="https://fonts.gstatic.com"]');
  await expect(preconnect).toHaveCount(1);

  const stylesheet = page.locator('link[rel="stylesheet"][href*="fonts.googleapis.com"]');
  await expect(stylesheet).toHaveCount(1);
});
