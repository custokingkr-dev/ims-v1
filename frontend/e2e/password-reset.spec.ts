import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

for (const width of [375, 1280]) {
  test(`password recovery is accessible and retains safe retry at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    let requests = 0;
    await page.route('**/api/v1/auth/password-reset/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('/capabilities')) return route.fulfill({ json: { enabled: true } });
      requests++;
      return route.fulfill({ status: 202, json: { message: 'If the account is eligible, a reset link will be sent.' } });
    });
    await page.goto('/reset-password');
    await page.getByLabel('Email address').fill('synthetic@example.invalid');
    await page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(page.getByRole('status')).toContainText('If the account is eligible');
    expect(requests).toBe(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    expect((await new AxeBuilder({ page }).include('main').analyze()).violations).toEqual([]);
    await page.screenshot({ path: `../artifacts/product-followup-2026-09-26/reset-request-${width}.png`, fullPage: true });
  });

  test(`password link keeps its secret out of requests and offers an expired-link recovery at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route('**/api/v1/auth/password-reset/**', async route => {
      if (route.request().method() === 'GET') return route.fulfill({ json: { enabled: true } });
      return route.fulfill({ status: 400, json: { message: 'Reset link expired' } });
    });
    await page.goto(`/reset-password#token=${'A'.repeat(43)}`);
    await expect(page).toHaveURL(/\/reset-password$/);
    await page.getByLabel('New password', { exact: true }).fill('a-synthetic-password');
    await page.getByLabel('Confirm new password').fill('a-synthetic-password');
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByRole('alert')).toContainText('invalid or expired');
    await expect(page.getByRole('alert')).toBeFocused();
    expect((await new AxeBuilder({ page }).include('main').analyze()).violations).toEqual([]);
    await page.screenshot({ path: `../artifacts/product-followup-2026-09-26/reset-confirm-${width}.png`, fullPage: true });
    await page.getByRole('button', { name: 'Request a new link' }).click();
    await expect(page.getByLabel('Email address')).toBeVisible();
  });
}
