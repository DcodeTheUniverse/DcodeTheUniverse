import { expect, type Page, test } from '@playwright/test';
import { fresh, NAMES, setupGame } from './helpers';

async function toFirstCard(page: Page) {
  await fresh(page);
  await setupGame(page);
  await page.getByRole('button', { name: 'Start game' }).click();
  await page.getByRole('button', { name: `I’m ${NAMES[0]} — open my card` }).click();
}

/** True if the secret face (word or bluffer text) exists anywhere in the DOM. */
const secretInDom = (page: Page) =>
  page.evaluate(() => /You’re the bluffer\.|Secret word/.test(document.body.innerHTML));

test('covered cards are not in the DOM, and release covers immediately', async ({ page }) => {
  await toFirstCard(page);
  expect(await secretInDom(page)).toBe(false);
  const hold = page.locator('button', { hasText: /Hold to reveal|Release to cover/ });
  await hold.dispatchEvent('pointerdown', { button: 0, pointerId: 1, pointerType: 'touch' });
  expect(await secretInDom(page)).toBe(true);
  await hold.dispatchEvent('pointercancel', { pointerId: 1, pointerType: 'touch' });
  expect(await secretInDom(page)).toBe(false);
  // No secret in attributes or accessible names either.
  const attrs = await page.evaluate(() =>
    Array.from(document.querySelectorAll('*'))
      .flatMap((el) => Array.from(el.attributes).map((a) => a.value))
      .join(' '),
  );
  expect(attrs).not.toMatch(/bluffer\.|Secret word/);
});

test('window blur and visibility changes cover the card; backgrounding pauses the round', async ({
  page,
}) => {
  await toFirstCard(page);
  await page.getByRole('button', { name: /Tap to show for 5 seconds/ }).click();
  expect(await secretInDom(page)).toBe(true);
  await page.evaluate(() => window.dispatchEvent(new Event('blur')));
  expect(await secretInDom(page)).toBe(false);

  await page.getByRole('button', { name: /Tap to show for 5 seconds/ }).click();
  expect(await secretInDom(page)).toBe(true);
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  expect(await secretInDom(page)).toBe(false);
  await expect(page.getByRole('heading', { name: 'Round paused' })).toBeVisible();
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  });
  await page.getByRole('button', { name: 'Resume round' }).click();
  // Resumes on the neutral handover, never straight onto the private card.
  await expect(page.getByRole('heading', { name: `Pass the phone to ${NAMES[0]}.` })).toBeVisible();
});

test('accessible tap mode auto-covers after a short timeout', async ({ page }) => {
  await page.clock.install();
  await toFirstCard(page);
  await page.getByRole('button', { name: /Tap to show for 5 seconds/ }).click();
  expect(await secretInDom(page)).toBe(true);
  await page.clock.runFor(5100);
  expect(await secretInDom(page)).toBe(false);
  await expect(page.getByRole('button', { name: /^Done — pass the phone/ })).toBeEnabled();
});

test('done is disabled until the card was seen and is covered', async ({ page }) => {
  await toFirstCard(page);
  const done = page.getByRole('button', { name: /^Done — pass the phone/ });
  await expect(done).toBeDisabled();
  await page.getByRole('button', { name: /Tap to show for 5 seconds/ }).click();
  await expect(done).toBeDisabled();
  await page.getByRole('button', { name: 'Hide now' }).click();
  await expect(done).toBeEnabled();
});

test('a reload mid-round restores a neutral, paused recovery screen', async ({ page }) => {
  await toFirstCard(page);
  await page.getByRole('button', { name: /Tap to show for 5 seconds/ }).click();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  expect(await secretInDom(page)).toBe(false);
  await page.getByRole('button', { name: 'Resume round' }).click();
  await expect(page.getByRole('heading', { name: `Pass the phone to ${NAMES[0]}.` })).toBeVisible();
});

test('a reload during voting keeps cast votes and clears the open selection', async ({ page }) => {
  await fresh(page);
  await setupGame(page);
  await page.getByRole('button', { name: 'Start game' }).click();
  for (const name of NAMES) {
    await page.getByRole('button', { name: `I’m ${name} — open my card` }).click();
    await page.getByRole('button', { name: /Tap to show/ }).click();
    await page.getByRole('button', { name: 'Hide now' }).click();
    await page.getByRole('button', { name: /^Done — / }).click();
  }
  await page.getByRole('button', { name: 'Start discussion' }).click();
  await page.getByRole('button', { name: 'Start voting' }).click();
  await page.getByRole('button', { name: `I’m ${NAMES[0]} — vote privately` }).click();
  await page.getByRole('radio', { name: NAMES[1] as string }).click();
  await page.getByRole('button', { name: 'Lock in my vote' }).click();
  await page.getByRole('button', { name: `I’m ${NAMES[1]} — vote privately` }).click();
  await page.getByRole('radio', { name: NAMES[0] as string }).click();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  await page.getByRole('button', { name: 'Resume round' }).click();
  await expect(page.getByText('Voter 2 of 4')).toBeVisible();
  await page.getByRole('button', { name: `I’m ${NAMES[1]} — vote privately` }).click();
  await expect(page.getByRole('radio', { checked: true })).toHaveCount(0);
});

test('a corrupted saved game is discarded with an explanation, keeping settings', async ({
  page,
}) => {
  await fresh(page);
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('switch', { name: 'Sound' }).click();
  await page.evaluate(() =>
    localStorage.setItem(
      'CapacitorStorage.bc.session',
      JSON.stringify({ v: 1, savedAt: 1, data: { nope: true } }),
    ),
  );
  await page.reload();
  await expect(page.getByText(/A saved game could not be restored/)).toBeVisible();
  await page.getByRole('button', { name: 'Settings' }).click();
  await expect(page.getByRole('switch', { name: 'Sound' })).toHaveAttribute(
    'aria-checked',
    'false',
  );
});
