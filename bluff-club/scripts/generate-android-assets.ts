/**
 * pnpm android:assets — renders the original SVG artwork in assets/ to the PNG sizes
 * the Android project expects (launcher, adaptive foreground, round, splash), using
 * the locally installed Playwright Chromium. No network, no third-party image tools.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { chromium } from '@playwright/test';

const root = new URL('..', import.meta.url).pathname;
const res = join(root, 'android/app/src/main/res');
const icon = readFileSync(join(root, 'assets/icon.svg'), 'utf8');
const foreground = readFileSync(join(root, 'assets/icon-foreground.svg'), 'utf8');

const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 } as const;

const html = (body: string, w: number, h: number, bg = 'transparent') =>
  `<!doctype html><html><body style="margin:0;width:${w}px;height:${h}px;background:${bg};overflow:hidden">${body}</body></html>`;
const svgBox = (svg: string, w: number, h: number, extra = '') =>
  `<div style="width:${w}px;height:${h}px;${extra}">${svg.replace('<svg ', `<svg width="${w}" height="${h}" `)}</div>`;

function splash(w: number, h: number): string {
  const mark = Math.round(Math.min(w, h) * 0.32);
  const font = Math.round(Math.min(w, h) * 0.085);
  return `<div style="width:${w}px;height:${h}px;background:#101216;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:${Math.round(font * 0.4)}px;font-family:system-ui,sans-serif">
    ${foreground.replace('<svg ', `<svg width="${mark}" height="${mark}" `)}
    <div style="font-weight:900;font-size:${font}px;letter-spacing:-0.04em;color:#F6F5EF;text-transform:uppercase">Bluff <span style="color:#C7FF5E">Club</span></div>
  </div>`;
}

async function main() {
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  const shot = async (
    content: string,
    w: number,
    h: number,
    out: string,
    omitBackground = true,
  ) => {
    await page.setViewportSize({ width: w, height: h });
    await page.setContent(content);
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(
      out,
      await page.screenshot({ omitBackground, clip: { x: 0, y: 0, width: w, height: h } }),
    );
  };

  for (const [name, scale] of Object.entries(densities)) {
    const dir = join(res, `mipmap-${name}`);
    const legacy = Math.round(48 * scale);
    const fg = Math.round(108 * scale);
    await shot(
      html(svgBox(icon, legacy, legacy, 'border-radius:18%;overflow:hidden'), legacy, legacy),
      legacy,
      legacy,
      join(dir, 'ic_launcher.png'),
    );
    await shot(
      html(svgBox(icon, legacy, legacy, 'border-radius:50%;overflow:hidden'), legacy, legacy),
      legacy,
      legacy,
      join(dir, 'ic_launcher_round.png'),
    );
    await shot(
      html(svgBox(foreground, fg, fg), fg, fg),
      fg,
      fg,
      join(dir, 'ic_launcher_foreground.png'),
    );
    // Android 12+ splash icon: 240dp canvas with the mark inside the 160dp circle.
    const sp = Math.round(240 * scale);
    await shot(
      html(svgBox(foreground, sp, sp), sp, sp),
      sp,
      sp,
      join(res, `drawable-${name}`, 'ic_splash_mark.png'),
    );
  }

  const sizes: [string, number, number][] = [
    ['drawable', 480, 320],
    ['drawable-port-mdpi', 320, 480],
    ['drawable-port-hdpi', 480, 800],
    ['drawable-port-xhdpi', 720, 1280],
    ['drawable-port-xxhdpi', 960, 1600],
    ['drawable-port-xxxhdpi', 1280, 1920],
    ['drawable-land-mdpi', 480, 320],
    ['drawable-land-hdpi', 800, 480],
    ['drawable-land-xhdpi', 1280, 720],
    ['drawable-land-xxhdpi', 1600, 960],
    ['drawable-land-xxxhdpi', 1920, 1280],
  ];
  for (const [dir, w, h] of sizes)
    await shot(html(splash(w, h), w, h, '#101216'), w, h, join(res, dir, 'splash.png'), false);

  // Store listing icon (512×512, no transparency) for Play Console.
  await shot(
    html(svgBox(icon, 512, 512), 512, 512, '#101216'),
    512,
    512,
    join(root, 'docs/store/icon-512.png'),
    false,
  );
  await browser.close();
  console.log('✓ Android icons, splash screens and store icon generated.');
}

void main();
