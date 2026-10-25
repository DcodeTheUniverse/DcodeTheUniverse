import { createSignal, onCleanup, onMount, Show } from 'solid-js';
import { useApp } from '../../app/context';
import { cx, useOnChange } from '../../components/ui';
import {
  elapsedMs,
  isExpired,
  isRunning,
  remainingMs,
  type TimerState,
} from '../../domain/game/timer';

export function formatClock(ms: number): string {
  const total = Math.ceil(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/** Re-renders from timestamps; the interval only triggers repaint, never counts time. */
export function TimerDisplay(props: { timer: TimerState; expiredText: string }) {
  const app = useApp();
  const [now, setNow] = createSignal(app.now());
  let handle: ReturnType<typeof setInterval> | undefined;
  onMount(() => {
    handle = setInterval(() => setNow(app.now()), 250);
  });
  onCleanup(() => clearInterval(handle));

  const remaining = () => remainingMs(props.timer, now());
  const expired = () => isExpired(props.timer, now());
  const fraction = () => {
    const d = props.timer.durationMs;
    return d ? Math.min(1, elapsedMs(props.timer, now()) / d) : 0;
  };

  useOnChange(expired, () => {
    if (expired()) app.feedback('warning', 'timer-end');
  });

  return (
    <div class="text-center">
      <Show
        when={props.timer.durationMs !== null}
        fallback={
          <p class="text-2xl font-black text-muted">No timer — talk as long as you like.</p>
        }
      >
        <p
          class={cx(
            'font-black tabular-nums tracking-tight',
            expired() ? 'text-danger' : 'text-cream',
          )}
          style={{ 'font-size': 'clamp(3.5rem, 18vw, 6rem)', 'line-height': '1' }}
          aria-hidden="true"
        >
          {formatClock(remaining() ?? 0)}
        </p>
        <div
          class="mx-auto mt-4 h-3 w-full max-w-sm overflow-hidden rounded-full bg-surface-2"
          aria-hidden="true"
        >
          <div
            class={cx(
              'h-full rounded-full transition-[width] duration-200',
              expired() ? 'bg-danger' : 'bg-lime',
            )}
            style={{ width: `${(1 - fraction()) * 100}%` }}
          />
        </div>
        <p class="sr-only">
          {expired() ? props.expiredText : `${Math.ceil((remaining() ?? 0) / 1000)} seconds left`}
        </p>
        <Show when={!isRunning(props.timer) && !expired() && props.timer.started}>
          <p class="mt-3 text-lg font-bold text-muted">Paused</p>
        </Show>
      </Show>
      <Show when={expired()}>
        <p role="status" class="mt-4 text-xl font-black text-danger">
          ⏰ {props.expiredText}
        </p>
      </Show>
    </div>
  );
}
