import { test } from '@playwright/test';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const root = path.resolve(process.cwd(), '../.impeccable/mocks/decision');
for (const name of ['meter', 'audit', 'orienteering', 'segment']) {
  test(`comp ${name}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(pathToFileURL(path.join(root, 'src', `${name}.html`)).href);
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(root, `${name}.png`) });
  });
}
