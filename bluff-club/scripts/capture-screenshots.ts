/**
 * pnpm screenshots — captures REAL screens of the implemented UI from a production
 * build (synthetic player names, no fabricated features) into docs/screenshots/.
 * Requires `pnpm build` first. Purchase screens are not captured from the browser
 * build because purchasing is (correctly) unavailable there.
 */
import { type ChildProcess, spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { chromium, devices, type Page } from '@playwright/test';

const root = new URL('..', import.meta.url).pathname;
const out = join(root, 'docs/screenshots');
const PORT = 4311;
const NAMES = ['Lerato', 'Pieter', 'Aisha', 'Kabelo', 'Megan'];

function startPreview(): ChildProcess {
  return spawn('pnpm', ['exec', 'vite', 'preview', '--port', String(PORT), '--strictPort'], {
    cwd: root,
    stdio: 'ignore',
  });
}

async function waitForServer(url: string) {
  for (let i = 0; i < 50; i++) {
    try {
      if ((await fetch(url)).ok) return;
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error('preview server did not start');
}

async function main() {
  mkdirSync(out, { recursive: true });
  const server = startPreview();
  try {
    await waitForServer(`http://localhost:${PORT}`);
    const browser = await chromium.launch();
    const context = await browser.newContext({ ...devices['Pixel 7'], reducedMotion: 'reduce' });
    const page = await context.newPage();
    const shot = (name: string) => page.screenshot({ path: join(out, `${name}.png`) });
    await page.goto(`http://localhost:${PORT}`);
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.getByRole('heading', { name: /bluff club/i }).waitFor();
    await shot('01-home');

    await page.getByRole('button', { name: 'Play', exact: true }).click();
    const inputs = page.getByRole('textbox');
    await page.getByRole('button', { name: '+ Add player' }).click();
    for (let i = 0; i < NAMES.length; i++) await inputs.nth(i).fill(NAMES[i] as string);
    await shot('02-setup');

    await page.getByRole('button', { name: 'Start game' }).click();
    await shot('03-pass-the-phone');
    await page.getByRole('button', { name: /open my card/ }).click();
    await page.getByRole('button', { name: /Tap to show/ }).click();
    await shot('04-private-card');
    await page.getByRole('button', { name: 'Hide now' }).click();
    await page.getByRole('button', { name: /^Done — / }).click();
    for (let i = 1; i < NAMES.length; i++) {
      await page.getByRole('button', { name: /open my card/ }).click();
      await page.getByRole('button', { name: /Tap to show/ }).click();
      await page.getByRole('button', { name: 'Hide now' }).click();
      await page.getByRole('button', { name: /^Done — / }).click();
    }
    await shot('05-clue-order');
    await page.getByRole('button', { name: 'Start discussion' }).click();
    await page.waitForTimeout(1200);
    await shot('06-discussion-timer');
    await page.getByRole('button', { name: 'Start voting' }).click();
    await page.getByRole('button', { name: /vote privately/ }).click();
    await page.getByRole('radio').nth(1).click();
    await shot('07-private-vote');
    await page.getByRole('button', { name: 'Lock in my vote' }).click();
    await voteRest(page);
    await page.waitForTimeout(300);
    await shot('08-results');
    await browser.close();
    console.log(`✓ Screenshots written to ${out}`);
  } finally {
    server.kill();
  }
}

async function voteRest(page: Page) {
  for (let i = 1; i < NAMES.length; i++) {
    await page.getByRole('button', { name: /vote privately/ }).click();
    await page.getByRole('radio').first().click();
    await page.getByRole('button', { name: 'Lock in my vote' }).click();
    if (await page.getByText('It’s a tie').isVisible()) {
      await page.getByRole('button', { name: 'Start runoff vote' }).click();
      return voteRest2(page);
    }
  }
}

async function voteRest2(page: Page) {
  for (let i = 0; i < NAMES.length; i++) {
    await page.getByRole('button', { name: /vote privately/ }).click();
    await page.getByRole('radio').first().click();
    await page.getByRole('button', { name: 'Lock in my vote' }).click();
  }
}

void main();
