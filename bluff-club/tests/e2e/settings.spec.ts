import { expect, test } from '@playwright/test';
import { fresh } from './helpers';

test('settings persist across reloads', async ({ page }) => {
  await fresh(page);
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('switch', { name: 'Haptics' }).click();
  await page.getByRole('button', { name: 'Tap (auto-hides)' }).click();
  await page.reload();
  await page.getByRole('button', { name: 'Settings' }).click();
  await expect(page.getByRole('switch', { name: 'Haptics' })).toHaveAttribute(
    'aria-checked',
    'false',
  );
  await expect(page.getByRole('button', { name: 'Tap (auto-hides)' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect(page.getByText(/support contact is not configured/)).toBeVisible();
  await expect(page.getByText(/Resetting never cancels or refunds a purchase/)).toBeVisible();
});

test('the tutorial is skippable, stops showing on home, and stays reachable from settings', async ({
  page,
}) => {
  await fresh(page);
  await page.getByRole('button', { name: 'How to play →' }).click();
  await expect(page.getByText('Card 1 of 3')).toBeVisible();
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByRole('button', { name: 'Next' }).click();
  await expect(page.getByText('Card 3 of 3')).toBeVisible();
  await page.getByRole('button', { name: 'Got it' }).click();
  await expect(page.getByRole('button', { name: 'How to play →' })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole('button', { name: 'How to play →' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Show the tutorial' }).click();
  await page.getByRole('button', { name: 'Skip' }).click();
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
});

test('reset game data asks first and keeps the player list', async ({ page }) => {
  await fresh(page);
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.getByRole('textbox').first().fill('Nomsa');
  await page.getByRole('button', { name: 'Start game' }).click();
  await page.getByRole('button', { name: 'Pause round' }).click();
  await page.getByRole('button', { name: /Go to home screen/ }).click();
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Clear scores & saved round' }).click();
  const dialog = page.getByRole('dialog', { name: 'Clear scores and saved round?' });
  await expect(dialog.getByText(/Purchases are not affected/)).toBeVisible();
  await dialog.getByRole('button', { name: 'Clear game data' }).click();
  await page.getByRole('button', { name: 'Back' }).click();
  await expect(page.getByRole('button', { name: 'Resume round' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await expect(page.getByRole('textbox').first()).toHaveValue('Nomsa');
});

test('keyboard users see a focus ring and can close dialogs with Escape', async ({ page }) => {
  await fresh(page);
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.getByRole('radio', { name: /Wild Card/ }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.keyboard.press('Tab');
  const outline = await page.evaluate(
    () => getComputedStyle(document.activeElement as Element).outlineStyle,
  );
  expect(outline).toBe('solid');
});
