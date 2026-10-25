import { expect, type Page, test } from '@playwright/test';
import { fresh, NAMES, setupGame } from './helpers';

test.use({ viewport: { width: 360, height: 740 } });

async function noHorizontalOverflow(page: Page, label: string) {
  const { scroll, width } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    width: window.innerWidth,
  }));
  expect(scroll, `${label} overflows horizontally`).toBeLessThanOrEqual(width);
}

async function smallTargets(page: Page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('button, a[href], input, [role="switch"], [role="radio"]'))
      .filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && (r.height < 47.5 || r.width < 47.5);
      })
      .map((el) =>
        (el.textContent || el.getAttribute('aria-label') || el.tagName).trim().slice(0, 40),
      ),
  );
}

for (const scale of [1, 2]) {
  test(`360px layout at ${scale * 100}% text has no horizontal scroll and 48px targets`, async ({
    page,
  }) => {
    await fresh(page);
    await page.addStyleTag({ content: `html { font-size: ${scale * 100}% !important; }` });
    const visit = async (label: string) => {
      await page.waitForTimeout(250); // let the 180ms pop-in animation settle
      await page.addStyleTag({ content: `html { font-size: ${scale * 100}% !important; }` });
      await noHorizontalOverflow(page, label);
      expect(await smallTargets(page), `${label} small targets`).toEqual([]);
    };
    await visit('home');
    await setupGame(page, [...NAMES, 'Siphosethu-Lindiwe M']);
    await visit('setup');
    await page.getByRole('button', { name: 'Start game' }).click();
    await visit('handover');
    await page.getByRole('button', { name: /open my card/ }).click();
    await visit('reveal');
    await page.getByRole('button', { name: 'Pause round' }).click();
    await visit('paused');
    await page.getByRole('button', { name: /Go to home screen/ }).click();
    await page.getByRole('button', { name: 'Packs' }).click();
    await visit('packs');
    await page.getByRole('button', { name: 'View bundle' }).click();
    await visit('paywall');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'Back' }).click();
    await page.getByRole('button', { name: 'Settings' }).click();
    await visit('settings');
  });
}
