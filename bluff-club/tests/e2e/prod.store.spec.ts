import { expect, test } from '@playwright/test';

test('production browser build has no demo store and shows purchasing as unavailable', async ({
  page,
}) => {
  await page.goto('/');
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await page.getByRole('button', { name: 'Packs' }).click();
  await expect(page.getByText('Purchases unavailable')).toBeVisible();
  await expect(page.getByText(/Demo/)).toHaveCount(0);
  await page.getByRole('button', { name: 'View pack' }).first().click();
  const dialog = page.getByRole('dialog', { name: 'Food Fight' });
  await expect(dialog.getByRole('button', { name: 'Buy' })).toBeDisabled();
  await dialog.getByRole('button', { name: 'Restore purchases' }).click();
  await expect(
    dialog.getByText(/only available in the Bluff Club Android app/).first(),
  ).toBeVisible();

  // The demo store's code is not shipped at all.
  const scripts = await page.evaluate(() =>
    Array.from(document.scripts)
      .map((s) => s.src)
      .filter(Boolean),
  );
  for (const src of scripts) {
    const body = await (await page.request.get(src)).text();
    expect(body).not.toContain('Demo purchase — no charge');
    expect(body).not.toContain('demo-store.purchases');
  }
});

test('free game is fully playable in the production build', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.getByRole('button', { name: 'Start game' }).click();
  await expect(page.getByRole('heading', { name: /Pass the phone to/ })).toBeVisible();
});
