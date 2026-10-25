import { createSignal, onCleanup, onMount, Show } from 'solid-js';
import { useApp } from '../../app/context';
import { Button, cx, useOnChange } from '../../components/ui';

export const AUTO_COVER_MS = 5000;

/**
 * Private role card. The secret subtree is only MOUNTED while revealed; when covered
 * it is removed from the DOM entirely (not blurred, not opacity-hidden), and nothing
 * secret is ever placed in attributes or accessibility labels.
 *
 * Covering happens on: release, pointer cancel/leave, window blur, visibility change,
 * native background events (via the controller's cover signal) and a 5 s timeout for
 * the tap-to-reveal mode.
 */
export function SecretCard(props: {
  packName: string;
  /** Only read while revealed. */
  secret: () => { kind: 'word'; word: string } | { kind: 'bluffer' };
  onSeen: () => void;
  onVisibleChange?: (visible: boolean) => void;
}) {
  const app = useApp();
  const [visible, setVisibleRaw] = createSignal(false);
  const [mode, setMode] = createSignal<'hold' | 'tap' | null>(null);
  let timer: ReturnType<typeof setTimeout> | undefined;

  const setVisible = (v: boolean, how: 'hold' | 'tap' | null = null) => {
    clearTimeout(timer);
    setVisibleRaw(v);
    setMode(v ? how : null);
    props.onVisibleChange?.(v);
    if (v) {
      props.onSeen();
      if (how === 'tap') timer = setTimeout(() => setVisible(false), AUTO_COVER_MS);
    }
  };
  const cover = () => {
    if (visible()) setVisible(false);
  };

  useOnChange(app.coverSignal, cover);
  const onVisibility = () => {
    if (document.visibilityState !== 'visible') cover();
  };
  onMount(() => {
    window.addEventListener('blur', cover);
    document.addEventListener('visibilitychange', onVisibility);
  });
  onCleanup(() => {
    clearTimeout(timer);
    window.removeEventListener('blur', cover);
    document.removeEventListener('visibilitychange', onVisibility);
  });

  const holdStart = (e: PointerEvent) => {
    if (e.button !== 0 && e.pointerType === 'mouse') return;
    e.preventDefault();
    try {
      (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    } catch {
      /* capture is best effort */
    }
    setVisible(true, 'hold');
  };
  const holdEnd = () => {
    if (mode() === 'hold') setVisible(false);
  };
  const keyDown = (e: KeyboardEvent) => {
    if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) {
      e.preventDefault();
      setVisible(true, 'hold');
    }
  };
  const keyUp = (e: KeyboardEvent) => {
    if (e.key === ' ' || e.key === 'Enter') {
      e.preventDefault();
      holdEnd();
    }
  };

  const preferTap = () => app.settings().revealMode === 'tap';

  return (
    <div class="flex flex-col gap-4">
      <div
        class={cx(
          'no-callout relative flex min-h-[18rem] flex-col items-center justify-center rounded-[2rem] border-4 p-6 text-center shadow-pop transition-colors duration-100',
          visible() ? 'border-lime bg-surface-2' : 'border-line bg-surface',
        )}
      >
        <p class="text-sm font-bold tracking-widest text-muted uppercase">
          Pack · {props.packName}
        </p>
        <Show
          when={visible()}
          fallback={
            <div class="mt-4 flex flex-col items-center gap-3 text-muted">
              <svg width="64" height="64" viewBox="0 0 64 64" aria-hidden="true">
                <path
                  d="M12 10h40a8 8 0 0 1 8 8v22a8 8 0 0 1-8 8H31l-13 10v-10h-6a8 8 0 0 1-8-8V18a8 8 0 0 1 8-8z"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="3"
                />
                <path
                  d="M18 29h28"
                  stroke="currentColor"
                  stroke-width="3"
                  stroke-linecap="round"
                  stroke-dasharray="4 6"
                />
              </svg>
              <p class="text-xl font-bold text-cream">Card covered</p>
            </div>
          }
        >
          <SecretFace secret={props.secret()} />
        </Show>
      </div>

      <Show
        when={!preferTap()}
        fallback={
          <Button
            size="xl"
            block
            variant={visible() ? 'secondary' : 'primary'}
            onClick={() => (visible() ? setVisible(false) : setVisible(true, 'tap'))}
          >
            {visible() ? 'Hide my card' : 'Show my card'}
          </Button>
        }
      >
        <button
          type="button"
          class="no-callout min-h-20 w-full rounded-2xl bg-lime px-6 text-xl font-black text-ink shadow-pop select-none active:translate-y-[3px] active:shadow-pop-sm"
          onPointerDown={holdStart}
          onPointerUp={holdEnd}
          onPointerCancel={holdEnd}
          onLostPointerCapture={holdEnd}
          onPointerLeave={holdEnd}
          onKeyDown={keyDown}
          onKeyUp={keyUp}
          onBlur={holdEnd}
          onContextMenu={(e) => e.preventDefault()}
        >
          {visible() && mode() === 'hold' ? 'Release to cover' : 'Hold to reveal'}
        </button>
      </Show>

      <Show when={!preferTap()}>
        <Button
          variant="ghost"
          size="md"
          onClick={() => (visible() ? setVisible(false) : setVisible(true, 'tap'))}
        >
          {visible() && mode() === 'tap' ? 'Hide now' : 'Can’t hold? Tap to show for 5 seconds'}
        </Button>
      </Show>
      <Show when={preferTap()}>
        <p class="text-center text-muted">Your card hides itself after 5 seconds.</p>
      </Show>
    </div>
  );
}

function SecretFace(props: { secret: { kind: 'word'; word: string } | { kind: 'bluffer' } }) {
  // Both faces share the same layout, colours and size so they look alike from a distance.
  return (
    <div class="mt-4 flex flex-col items-center gap-3">
      <Show
        when={props.secret.kind === 'word' ? props.secret : null}
        fallback={
          <>
            <p class="text-sm font-bold tracking-widest text-muted uppercase">Your role</p>
            <p class="text-4xl leading-tight font-black tracking-tight break-words text-cream">
              You’re the bluffer.
            </p>
            <p class="text-lg text-cream/90">Blend in. Don’t get caught.</p>
          </>
        }
      >
        {(s) => (
          <>
            <p class="text-sm font-bold tracking-widest text-muted uppercase">Secret word</p>
            <p class="text-4xl leading-tight font-black tracking-tight break-words text-cream">
              {s().word}
            </p>
            <p class="text-lg text-cream/90">Don’t say it. Give a clue that proves you know it.</p>
          </>
        )}
      </Show>
    </div>
  );
}
