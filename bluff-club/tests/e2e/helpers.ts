import { expect, type Page } from '@playwright/test';

export const NAMES = ['Ayanda', 'Bea', 'Chen', 'Dewald'];

export async function fresh(page: Page) {
  await page.goto('/');
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await expect(page.getByRole('heading', { name: /bluff club/i })).toBeVisible();
}

export async function setupGame(page: Page, names: string[] = NAMES) {
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  const inputs = page.getByRole('textbox');
  while ((await inputs.count()) < names.length)
    await page.getByRole('button', { name: '+ Add player' }).click();
  while ((await inputs.count()) > names.length) {
    await page
      .getByRole('button', { name: /^Remove / })
      .last()
      .click();
  }
  for (let i = 0; i < names.length; i++) await inputs.nth(i).fill(names[i] as string);
}

/** Reveals every card with hold-to-reveal and returns the bluffer's name and the word. */
export async function revealAll(page: Page, names: string[] = NAMES) {
  let bluffer = '';
  let word = '';
  for (const name of names) {
    await expect(page.getByRole('heading', { name: `Pass the phone to ${name}.` })).toBeVisible();
    await page.getByRole('button', { name: `I’m ${name} — open my card` }).click();
    const hold = page.getByRole('button', { name: 'Hold to reveal' });
    const box = await hold.boundingBox();
    if (!box) throw new Error('no hold button');
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    const face = page.getByText(/You’re the bluffer\.|Secret word/).first();
    await expect(face).toBeVisible();
    if (await page.getByText('You’re the bluffer.').isVisible()) {
      bluffer = name;
    } else {
      word = (await page.locator('p.text-4xl').innerText()).trim();
    }
    await page.mouse.up();
    await expect(page.getByText('Card covered')).toBeVisible();
    await page.getByRole('button', { name: /^Done — / }).click();
  }
  return { bluffer, word };
}

export async function voteAll(page: Page, names: string[], choose: (voter: string) => string) {
  for (const voter of names) {
    await page.getByRole('button', { name: `I’m ${voter} — vote privately` }).click();
    await page.getByRole('radio', { name: choose(voter) }).click();
    await page.getByRole('button', { name: 'Lock in my vote' }).click();
  }
}
