import { expect, type Page, test } from '@playwright/test';
import { fresh } from './helpers';

async function openPacks(page: Page) {
  await fresh(page);
  await page.getByRole('button', { name: 'Packs' }).click();
  await expect(page.getByRole('heading', { name: 'Packs', exact: true })).toBeVisible();
  await expect(page.getByText('Demo store', { exact: true })).toBeVisible();
}

async function setOutcome(page: Page, label: string) {
  await page
    .getByRole('group', { name: 'Next demo purchase result' })
    .getByRole('button', { name: label, exact: true })
    .click();
}

async function openFoodFight(page: Page) {
  await page.getByRole('button', { name: 'View pack' }).first().click();
  const dialog = page.getByRole('dialog', { name: 'Food Fight' });
  await expect(dialog).toBeVisible();
  return dialog;
}

test('locked packs cannot be selected in setup and open the paywall instead', async ({ page }) => {
  await fresh(page);
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.getByRole('radio', { name: /Food Fight/ }).click();
  const dialog = page.getByRole('dialog', { name: 'Food Fight' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/Once-off purchase/)).toBeVisible();
  await expect(dialog.getByText('Examples: Pancakes · Food truck · Midnight snack')).toBeVisible();
  await expect(dialog.getByText('Demo purchase — no charge')).toBeVisible();
  await dialog.getByRole('button', { name: 'Not now' }).click();
  await expect(page.getByRole('radio', { name: /General/ })).toBeChecked();
});

test('mock purchase success unlocks the pack and hides the bundle', async ({ page }) => {
  await openPacks(page);
  await expect(page.getByRole('heading', { name: 'Party Bundle' })).toBeVisible();
  const dialog = await openFoodFight(page);
  await dialog.getByRole('button', { name: /Buy once for R 39,00/ }).click();
  await expect(dialog.getByText('Unlocked! The pack is ready to play.')).toBeVisible();
  await expect(dialog.getByText('You own this pack. Enjoy!')).toBeVisible();
  await dialog.getByRole('button', { name: 'Done' }).click();
  await expect(page.getByRole('heading', { name: 'Party Bundle' })).toHaveCount(0);
  await expect(page.getByText(/so the bundle isn’t offered/)).toBeVisible();

  await page.getByRole('button', { name: 'Back' }).click();
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.getByRole('radio', { name: /Food Fight/ }).click();
  await expect(page.getByRole('radio', { name: /Food Fight/ })).toBeChecked();
});

test('cancel, pending (then approval), error and offline never unlock content', async ({
  page,
}) => {
  await openPacks(page);
  for (const [label, text] of [
    ['Cancel', 'Purchase cancelled. Nothing was charged.'],
    ['Error', 'Demo store: simulated purchase error.'],
    ['Offline', 'You’re offline. Connect to the internet to buy packs.'],
    ['Pending', 'Payment pending.'],
  ] as const) {
    await setOutcome(page, label);
    const dialog = await openFoodFight(page);
    await dialog.getByRole('button', { name: /Buy once/ }).click();
    await expect(dialog.getByText(text, { exact: false })).toBeVisible();
    await expect(dialog.getByText('You own this pack. Enjoy!')).toHaveCount(0);
    await dialog.getByRole('button', { name: 'Not now' }).click();
  }
  await expect(page.getByText('✓ Owned')).toHaveCount(0);
  await page.getByRole('button', { name: 'Approve pending demo payment' }).click();
  await expect(page.getByText('Purchase confirmed — new pack unlocked.')).toBeVisible();
  await expect(page.getByText('✓ Owned')).toHaveCount(1);
});

test('bundle grants exactly the four packs; refunds revoke; restore reports results', async ({
  page,
}) => {
  await openPacks(page);
  await page.getByRole('button', { name: 'View bundle' }).click();
  const dialog = page.getByRole('dialog', { name: 'Party Bundle' });
  await expect(dialog.getByText(/Future packs are not included/)).toBeVisible();
  await dialog.getByRole('button', { name: /Buy once for R 99,00/ }).click();
  await expect(dialog.getByText('You own all four packs. Enjoy!')).toBeVisible();
  await dialog.getByRole('button', { name: 'Done' }).click();
  await expect(page.getByText('✓ Owned')).toHaveCount(4);

  await page.getByRole('button', { name: 'Restore purchases' }).click();
  await expect(page.getByText('Restored 4 packs.')).toBeVisible();

  await page.getByRole('button', { name: 'Simulate refund of all demo purchases' }).click();
  await expect(page.getByText('✓ Owned')).toHaveCount(0);
  await page.getByRole('button', { name: 'Restore purchases' }).click();
  await expect(
    page.getByText('No previous pack purchases were found for this Google Play account.'),
  ).toBeVisible();
});

test('a purchase failure does not reset the current game', async ({ page }) => {
  await fresh(page);
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.getByRole('button', { name: 'Start game' }).click();
  await expect(page.getByText('Round 1 · General')).toBeVisible();
  await page.getByRole('button', { name: 'Pause round' }).click();
  await page.getByRole('button', { name: /Go to home screen/ }).click();
  await page.getByRole('button', { name: 'Packs' }).click();
  await setOutcome(page, 'Error');
  const dialog = await openFoodFight(page);
  await dialog.getByRole('button', { name: /Buy once/ }).click();
  await expect(dialog.getByText('Demo store: simulated purchase error.')).toBeVisible();
  await dialog.getByRole('button', { name: 'Not now' }).click();
  await page.getByRole('button', { name: 'Back' }).click();
  await page.getByRole('button', { name: 'Resume round' }).click();
  await expect(page.getByText('Round 1 · General')).toBeVisible();
});
