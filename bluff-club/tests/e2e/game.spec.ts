import { expect, test } from '@playwright/test';
import { fresh, NAMES, revealAll, setupGame, voteAll } from './helpers';

test('a complete free game, then a second round with kept scores', async ({ page }) => {
  await fresh(page);
  await setupGame(page);
  await page.getByRole('button', { name: 'Start game' }).click();

  const { bluffer, word } = await revealAll(page);
  expect(bluffer).not.toBe('');
  expect(word).not.toBe('');

  await expect(page.getByRole('heading', { name: 'Everyone has seen their card' })).toBeVisible();
  await page.getByRole('button', { name: 'Start discussion' }).click();
  await expect(page.getByText('1:30').or(page.getByText('1:29'))).toBeVisible();
  await page.getByRole('button', { name: 'Pause timer' }).click();
  await page.getByRole('button', { name: 'Resume timer' }).click();
  await page.getByRole('button', { name: 'Start voting' }).click();

  const someoneElse = NAMES.find((n) => n !== bluffer) as string;
  await voteAll(page, NAMES, (voter) => (voter === bluffer ? someoneElse : bluffer));

  await expect(page.getByRole('heading', { name: 'Bluffer caught!' })).toBeVisible();
  await expect(page.getByText(word, { exact: true })).toBeVisible();
  await expect(page.getByText('3 votes')).toBeVisible();
  // Public results never say who voted for whom.
  await expect(page.getByText(/voted for/i)).toHaveCount(0);

  await page.getByRole('button', { name: 'Play again' }).click();
  await expect(page.getByText('Round 2 · General')).toBeVisible();
  const second = await revealAll(page);
  expect(second.word).not.toBe(word); // deck never repeats before exhaustion
  await page.getByRole('button', { name: 'Start discussion' }).click();
  await page.getByRole('button', { name: 'Start voting' }).click();
  const innocent = NAMES.find((n) => n !== second.bluffer) as string;
  const other = NAMES.find((n) => n !== second.bluffer && n !== innocent) as string;
  await voteAll(page, NAMES, (voter) => (voter === innocent ? other : innocent));
  await expect(page.getByRole('heading', { name: 'The bluffer got away!' })).toBeVisible();
  // Round 1 gave 3 informed players 1 point; round 2 gave the bluffer 2 points.
  const scores = page.getByRole('heading', { name: 'Session scores' }).locator('..');
  await expect(scores).toContainText('+2');
});

test('a tie triggers one explained runoff; a repeated tie lets the bluffer win', async ({
  page,
}) => {
  const names = ['Ann', 'Ben', 'Cam', 'Dee'];
  await fresh(page);
  await setupGame(page, names);
  await page.getByRole('button', { name: 'Start game' }).click();
  await revealAll(page, names);
  await page.getByRole('button', { name: 'Start discussion' }).click();
  await page.getByRole('button', { name: 'Start voting' }).click();
  const plan: Record<string, string> = { Ann: 'Ben', Ben: 'Ann', Cam: 'Ann', Dee: 'Ben' };
  await voteAll(page, names, (v) => plan[v] as string);
  await expect(page.getByText('It’s a tie')).toBeVisible();
  await expect(
    page.getByText('Still tied? The group failed to agree, so the bluffer wins.'),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Start 20-second discussion' }).click();
  await expect(page.getByText(/0:(19|20)/)).toBeVisible();
  await page.getByRole('button', { name: 'Start runoff vote' }).click();
  // Candidates only: Cam may only choose Ann or Ben.
  await page.getByRole('button', { name: 'I’m Ann — vote privately' }).click();
  await expect(page.getByRole('radio')).toHaveCount(1);
  await page.getByRole('radio', { name: 'Ben' }).click();
  await page.getByRole('button', { name: 'Lock in my vote' }).click();
  for (const v of ['Ben', 'Cam', 'Dee']) {
    await page.getByRole('button', { name: `I’m ${v} — vote privately` }).click();
    if (v === 'Cam' || v === 'Dee') await expect(page.getByRole('radio')).toHaveCount(2);
    await page.getByRole('radio', { name: plan[v] as string }).click();
    await page.getByRole('button', { name: 'Lock in my vote' }).click();
  }
  await expect(page.getByRole('heading', { name: 'Deadlock — the bluffer wins!' })).toBeVisible();
  await expect(page.getByText('Runoff vote')).toBeVisible();
});

test('roster validation blocks blank and duplicate names', async ({ page }) => {
  await fresh(page);
  await setupGame(page, ['Zoë', 'zoë ', '']);
  await page.getByRole('button', { name: 'Start game' }).click();
  await expect(page.getByText('Two players can’t share a name.')).toBeVisible();
  await expect(page.getByText('Enter a name.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Game setup' })).toBeVisible();
});
