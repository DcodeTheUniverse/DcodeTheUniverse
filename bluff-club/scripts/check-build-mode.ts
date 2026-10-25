/**
 * Runs before `pnpm build` (production mode). Refuses to build when the demo store is
 * switched on through the shell environment or a production .env file. vite.config.ts
 * enforces the same rule; this gives an earlier, clearer error.
 */
import { existsSync, readFileSync } from 'node:fs';

const files = ['.env', '.env.local', '.env.production', '.env.production.local'];
const offenders: string[] = [];
if (process.env.VITE_ENABLE_MOCK_BILLING === 'true') offenders.push('environment variable');
for (const file of files) {
  if (!existsSync(file)) continue;
  if (/^\s*VITE_ENABLE_MOCK_BILLING\s*=\s*["']?true/m.test(readFileSync(file, 'utf8')))
    offenders.push(file);
}
if (offenders.length > 0) {
  console.error(`✗ Mock billing is enabled for a production build (${offenders.join(', ')}).`);
  console.error(
    '  Use `pnpm build:demo` for demos; production builds must never contain the demo store.',
  );
  process.exit(1);
}
console.log('✓ Production build mode: mock billing disabled.');
