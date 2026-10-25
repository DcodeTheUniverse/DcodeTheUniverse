/**
 * Colour tokens (mirrored in src/styles/app.css @theme; `pnpm release:check` verifies
 * they match). Contrast pairs are asserted in tests/unit/ui.test.tsx.
 */
export const COLORS = {
  ink: '#101216',
  surface: '#1c2029',
  'surface-2': '#262b36',
  line: '#3a4150',
  lime: '#c7ff5e',
  'lime-press': '#a9e03c',
  danger: '#ff6b6b',
  cream: '#f6f5ef',
  muted: '#adb3c2',
} as const;

export type ColorToken = keyof typeof COLORS;
