/**
 * pnpm content:validate — schema, counts, IDs, duplicates and lengths for bundled packs.
 * Prints near-duplicate review flags without failing: those need a human editor.
 * Passing this script does NOT prove that entries are fun, fair or balanced.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { validatePacks } from '../src/content/validation.ts';

const dir = new URL('../src/content/packs/', import.meta.url).pathname;
const files = readdirSync(dir).filter((f) => f.endsWith('.json'));
const packs: unknown[] = [];
const errors: string[] = [];
for (const file of files) {
  try {
    packs.push(JSON.parse(readFileSync(join(dir, file), 'utf8')));
  } catch (error) {
    errors.push(`${file}: invalid JSON (${(error as Error).message})`);
  }
}
const report = validatePacks(packs);
errors.push(...report.errors);

for (const pack of packs as { id: string; entries: unknown[]; tier: string }[]) {
  console.log(
    `  ${pack.id.padEnd(14)} ${String(pack.entries?.length ?? 0).padStart(4)} entries  (${pack.tier})`,
  );
}
if (report.reviewFlags.length > 0) {
  console.log(
    `\nEditorial review flags (${report.reviewFlags.length}) — possible near-duplicates, not failures:`,
  );
  for (const flag of report.reviewFlags) console.log(`  ? ${flag}`);
}
if (errors.length > 0) {
  console.error(`\n✗ ${errors.length} content error(s):`);
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}
console.log('\n✓ Content passed automated validation. Human playtesting is still required.');
