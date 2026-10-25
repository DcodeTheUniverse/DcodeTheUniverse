/**
 * Elapsed-time timer: progress is derived from timestamps, never from counting
 * interval ticks, so throttled or delayed intervals cannot make it drift.
 */
export interface TimerState {
  /** Total duration in ms, or null when the timer is switched off. */
  readonly durationMs: number | null;
  /** Elapsed time accumulated before the current running stretch. */
  readonly accumulatedMs: number;
  /** Epoch ms when the current running stretch began; null while paused/not started. */
  readonly runningSince: number | null;
  readonly started: boolean;
}

export function createTimer(durationMs: number | null): TimerState {
  return { durationMs, accumulatedMs: 0, runningSince: null, started: false };
}

export function elapsedMs(timer: TimerState, now: number): number {
  const running = timer.runningSince === null ? 0 : Math.max(0, now - timer.runningSince);
  return timer.accumulatedMs + running;
}

export function remainingMs(timer: TimerState, now: number): number | null {
  if (timer.durationMs === null) return null;
  return Math.max(0, timer.durationMs - elapsedMs(timer, now));
}

export function isExpired(timer: TimerState, now: number): boolean {
  return timer.durationMs !== null && elapsedMs(timer, now) >= timer.durationMs;
}

export function isRunning(timer: TimerState): boolean {
  return timer.runningSince !== null;
}

export function startTimer(timer: TimerState, now: number): TimerState {
  if (timer.runningSince !== null) return timer;
  return { ...timer, runningSince: now, started: true };
}

export function pauseTimer(timer: TimerState, now: number): TimerState {
  if (timer.runningSince === null) return timer;
  const elapsed = elapsedMs(timer, now);
  const capped = timer.durationMs === null ? elapsed : Math.min(elapsed, timer.durationMs);
  return { ...timer, accumulatedMs: capped, runningSince: null };
}
