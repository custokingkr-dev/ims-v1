import { expect, test, type Page } from '@playwright/test';
import { signInAs } from './support/session';

const approved = {
  id: 'live-review-1', title: 'School notice for tomorrow', message: 'Please bring the signed consent form tomorrow. Thank you.',
  audienceType: 'ALL_PARENTS', channels: ['EMAIL'], module: 'fees', status: 'APPROVED', schoolId: 7,
  communicationCategory: 'SCHOOL_NOTICE', approvalMode: 'LIVE', dispatchMode: null,
  createdAt: '2026-09-26T10:00:00Z', sentAt: null, scheduledAt: null,
};
const capabilities = {
  canCreateDraft: true, canApprove: true, canPreview: true, canSend: true, canQueue: true, mode: 'LIVE',
  sendUnavailableReason: '', queueUnavailableReason: '', supportedAudiences: ['ALL_PARENTS'],
  supportedChannels: ['EMAIL'], supportedCategories: ['SCHOOL_NOTICE'],
};
const preview = {
  broadcastId: approved.id, total: 4, eligible: 2, suppressed: 1, duplicate: 1,
  reasons: { CONTACT_NOT_VERIFIED: 1 }, fingerprint: 'reviewed-browser-fingerprint',
  explanation: 'Current consent and verified contacts were reviewed. Destinations remain private.',
};

async function openBroadcast(page: Page, lostResponse = false) {
  await signInAs(page, { role: 'ADMIN', permissions: ['school:read', 'fees:read', 'notifications:read', 'notifications:write'], modules: ['ERP'] });
  const submissions: unknown[] = [];
  await page.route('**/api/v1/notifications/broadcasts**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    let body: unknown = [approved];
    if (url.pathname.endsWith('/capabilities')) body = { ...capabilities, canSend: url.searchParams.has('schoolId'), canQueue: url.searchParams.has('schoolId') };
    if (url.pathname.endsWith('/preview')) body = preview;
    if (url.pathname.endsWith('/send')) {
      submissions.push(request.postDataJSON());
      if (lostResponse) return route.abort('failed');
    }
    if (url.pathname.endsWith('/send') || url.pathname.endsWith('/delivery-status')) {
      const status = lostResponse ? 'UNKNOWN' : 'ACCEPTED';
      body = { broadcastId: approved.id, status: lostResponse ? 'NEEDS_RECONCILIATION' : 'AWAITING_DELIVERY', mode: 'LIVE', approvalMode: 'LIVE', total: 2, delivered: 0,
        counts: { [status]: 2 }, recipients: [1, 2].map(studentId => ({ studentId, channel: 'EMAIL', status, reason: null, attempts: 1, nextAttemptAt: null, provider: 'msg91', providerMessageId: null, dryRun: false })) };
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto('/dashboard');
  await page.getByText('Broadcast drafts and approvals', { exact: true }).click();
  await page.getByRole('button', { name: 'Review live send' }).click();
  await expect(page.getByRole('dialog', { name: 'Confirm live sending' })).toBeVisible();
  return submissions;
}

for (const width of [320, 1440]) {
  test(`live confirmation is explicit, keyboard accessible, and fits at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    const submissions = await openBroadcast(page);
    const dialog = page.getByRole('dialog', { name: 'Confirm live sending' });
    await expect(dialog.getByText(approved.message, { exact: true })).toBeVisible();
    await expect(dialog.getByText('School 7. Channels: EMAIL.')).toBeVisible();
    await expect(dialog.getByText('2 eligible destinations; 1 excluded; 1 shared destinations skipped.')).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Send actual messages' })).toBeDisabled();
    expect(submissions).toHaveLength(0);
    const overflow = await dialog.evaluate(el => ({ scroll: el.scrollWidth, width: el.clientWidth, right: el.getBoundingClientRect().right, viewport: document.documentElement.clientWidth }));
    expect(overflow.scroll).toBeLessThanOrEqual(overflow.width + 1);
    expect(overflow.right).toBeLessThanOrEqual(overflow.viewport + 1);
    const checkbox = dialog.getByRole('checkbox', { name: /I reviewed the message and recipients/ });
    const acknowledgement = dialog.getByText('I reviewed the message and recipients and want to send actual messages.', { exact: true });
    const contentBounds = await acknowledgement.evaluate(el => ({ right: el.getBoundingClientRect().right, width: el.getBoundingClientRect().width, scroll: el.scrollWidth, client: el.clientWidth, dialogRight: el.closest('[role="dialog"]')!.getBoundingClientRect().right }));
    expect(contentBounds.right).toBeLessThanOrEqual(contentBounds.dialogRight);
    expect(contentBounds.width).toBeGreaterThan(200);
    expect(contentBounds.scroll).toBeLessThanOrEqual(contentBounds.client + 1);
    await checkbox.focus();
    await page.keyboard.press('Space');
    await expect(checkbox).toBeChecked();
    await page.screenshot({ path: testInfo.outputPath(`live-confirmation-${width}.png`) });
    await dialog.getByRole('button', { name: 'Send actual messages' }).click();
    await expect(page.getByText('Waiting for delivery reports', { exact: true })).toBeVisible();
    await expect(page.getByText('Confirmed deliveries: 0. Provider acceptance is not delivery confirmation.')).toBeVisible();
    expect(submissions).toEqual([{ mode: 'LIVE', previewFingerprint: preview.fingerprint }]);
    await expect(page.getByRole('button', { name: /Retry failed|Send actual|Review live/ })).toHaveCount(0);
  });
}

test('a lost live queue response only refreshes the same broadcast and never resends unknown outcomes', async ({ page }) => {
  const submissions = await openBroadcast(page, true);
  const dialog = page.getByRole('dialog', { name: 'Confirm live sending' });
  await dialog.getByRole('checkbox').check();
  await dialog.getByRole('button', { name: 'Send actual messages' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Messages may already be processing' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Review live send' })).toBeDisabled();
  await page.getByRole('button', { name: 'Refresh outcomes' }).click();
  await expect(page.getByText('Delivery needs reconciliation', { exact: true })).toBeVisible();
  await expect(page.getByText(/Delivery may have started. Do not resend this broadcast/)).toBeVisible();
  await page.getByRole('button', { name: 'Refresh outcomes' }).click();
  expect(submissions).toEqual([{ mode: 'LIVE', previewFingerprint: preview.fingerprint }]);
  await expect(page.getByRole('button', { name: /Retry failed|Send actual|Review live/ })).toHaveCount(0);
});
