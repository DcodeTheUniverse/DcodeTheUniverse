import { render, screen } from '@solidjs/testing-library';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppContext } from '../../src/app/context';
import { formatClock, TimerDisplay } from '../../src/features/game/Timer';
import { COLORS, type ColorToken } from '../../src/styles/tokens';
import { makeApp } from './harness';

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const v = Number.parseInt(hex.slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

describe('design tokens', () => {
  const token = (name: string) => COLORS[name as ColorToken];

  it.each([
    ['cream', 'ink'],
    ['cream', 'surface'],
    ['cream', 'surface-2'],
    ['muted', 'ink'],
    ['muted', 'surface'],
    ['muted', 'surface-2'],
    ['lime', 'ink'],
    ['lime', 'surface'],
    ['danger', 'ink'],
    ['danger', 'surface'],
    ['ink', 'lime'],
    ['ink', 'danger'],
  ])('%s on %s meets WCAG AA for normal text (≥ 4.5:1)', (fg, bg) => {
    expect(contrast(token(fg), token(bg))).toBeGreaterThanOrEqual(4.5);
  });
});

describe('TimerDisplay (controlled clock)', () => {
  afterEach(() => vi.useRealTimers());

  it('formats remaining time', () => {
    expect(formatClock(90_000)).toBe('1:30');
    expect(formatClock(59_001)).toBe('1:00');
    expect(formatClock(0)).toBe('0:00');
  });

  it('derives remaining time from timestamps and announces expiry without acting', async () => {
    const h = await makeApp();
    vi.useFakeTimers();
    const start = h.clock.now;
    render(() => (
      <AppContext.Provider value={h.app}>
        <TimerDisplay
          timer={{ durationMs: 60_000, accumulatedMs: 0, runningSince: start, started: true }}
          expiredText="Time’s up! Start voting when you’re ready."
        />
      </AppContext.Provider>
    ));
    expect(screen.getByText('1:00')).toBeInTheDocument();
    // Simulate a long throttled gap: the interval fires rarely but time is still right.
    h.clock.now = start + 45_500;
    vi.advanceTimersByTime(250);
    expect(screen.getByText('0:15')).toBeInTheDocument();
    h.clock.now = start + 61_000;
    vi.advanceTimersByTime(250);
    expect(screen.getByRole('status')).toHaveTextContent('Time’s up!');
    expect(h.app.session()).toBeNull(); // expiry never submits votes or ends a round
  });
});
