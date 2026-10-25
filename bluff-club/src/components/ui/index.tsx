import {
  createEffect,
  createUniqueId,
  type JSX,
  on,
  onCleanup,
  onMount,
  type ParentProps,
  Show,
  splitProps,
} from 'solid-js';

const cx = (...parts: (string | false | null | undefined)[]) => parts.filter(Boolean).join(' ');

// ---------------------------------------------------------------- Button

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

export interface ButtonProps extends JSX.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: 'md' | 'lg' | 'xl';
  block?: boolean;
  busy?: boolean;
}

const VARIANTS: Record<ButtonVariant, string> = {
  primary:
    'bg-lime text-ink shadow-pop active:translate-y-[3px] active:shadow-pop-sm hover:bg-lime-press disabled:bg-surface-2 disabled:text-muted disabled:shadow-none',
  secondary:
    'bg-surface-2 text-cream border-2 border-line shadow-pop active:translate-y-[3px] active:shadow-pop-sm hover:border-muted disabled:text-muted disabled:shadow-none',
  ghost: 'bg-transparent text-cream underline-offset-4 hover:underline disabled:text-muted',
  danger:
    'bg-danger text-ink shadow-pop active:translate-y-[3px] active:shadow-pop-sm disabled:bg-surface-2 disabled:text-muted disabled:shadow-none',
};

const SIZES = {
  md: 'min-h-12 px-4 text-base',
  lg: 'min-h-14 px-5 text-lg',
  xl: 'min-h-16 px-6 text-xl',
};

export function Button(props: ButtonProps) {
  const [local, rest] = splitProps(props, [
    'variant',
    'size',
    'block',
    'busy',
    'class',
    'children',
    'type',
  ]);
  return (
    <button
      type={local.type ?? 'button'}
      class={cx(
        'inline-flex items-center justify-center gap-2 rounded-2xl font-extrabold tracking-tight transition-[transform,box-shadow,background-color] duration-100 select-none disabled:cursor-not-allowed',
        VARIANTS[local.variant ?? 'primary'],
        SIZES[local.size ?? 'lg'],
        local.block && 'w-full',
        local.class,
      )}
      aria-busy={local.busy ? 'true' : undefined}
      {...rest}
    >
      <Show when={local.busy}>
        <Spinner small />
      </Show>
      {local.children}
    </button>
  );
}

// ---------------------------------------------------------------- Layout

export function Screen(
  props: ParentProps<{
    title?: string;
    onBack?: () => void;
    backLabel?: string;
    actions?: JSX.Element;
    footer?: JSX.Element;
    labelledBy?: string;
  }>,
) {
  const headingId = createUniqueId();
  let heading: HTMLHeadingElement | undefined;
  onMount(() => heading?.focus({ preventScroll: true }));
  return (
    <div class="club-bg safe-area flex min-h-dvh flex-col">
      <div class="mx-auto flex w-full max-w-xl flex-1 flex-col">
        <Show when={props.title || props.onBack || props.actions}>
          <header class="mb-4 flex min-h-12 items-center gap-2">
            <Show when={props.onBack}>
              <button
                type="button"
                onClick={() => props.onBack?.()}
                class="-ml-2 inline-flex min-h-12 min-w-12 shrink-0 items-center justify-center rounded-xl text-cream hover:bg-surface-2"
                aria-label={props.backLabel ?? 'Back'}
              >
                <svg width="26" height="26" viewBox="0 0 24 24" aria-hidden="true">
                  <path
                    d="M15 5l-7 7 7 7"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </button>
            </Show>
            <Show when={props.title}>
              <h1
                id={headingId}
                ref={heading}
                tabIndex={-1}
                class="flex-1 text-2xl font-black tracking-tight uppercase outline-none"
              >
                {props.title}
              </h1>
            </Show>
            {props.actions}
          </header>
        </Show>
        <main
          class="flex flex-1 flex-col"
          aria-labelledby={props.title ? headingId : props.labelledBy}
        >
          {props.children}
        </main>
        <Show when={props.footer}>
          <footer class="sticky bottom-0 z-20 -mx-1 mt-4 bg-ink/95 px-1 pt-3 pb-1 backdrop-blur-sm">
            {props.footer}
          </footer>
        </Show>
      </div>
    </div>
  );
}

export function Card(
  props: ParentProps<{ class?: string; tone?: 'default' | 'raised' | 'outline' }>,
) {
  return (
    <div
      class={cx(
        'rounded-[var(--radius-card)] p-5',
        props.tone === 'outline' ? 'border-2 border-line' : 'bg-surface',
        props.tone === 'raised' && 'shadow-pop',
        props.class,
      )}
    >
      {props.children}
    </div>
  );
}

// ---------------------------------------------------------------- Feedback

export function Spinner(props: { small?: boolean; label?: string }) {
  return (
    <span role={props.label ? 'status' : undefined} class="inline-flex items-center gap-2">
      <span
        aria-hidden="true"
        class={cx(
          'inline-block animate-spin rounded-full border-current border-r-transparent',
          props.small ? 'h-4 w-4 border-2' : 'h-6 w-6 border-[3px]',
        )}
      />
      <Show when={props.label}>
        <span>{props.label}</span>
      </Show>
    </span>
  );
}

export function Notice(
  props: ParentProps<{
    tone?: 'info' | 'warning' | 'error' | 'success';
    title?: string;
    class?: string;
    live?: boolean;
  }>,
) {
  const tone = () => props.tone ?? 'info';
  const icon = () => ({ info: 'i', warning: '!', error: '×', success: '✓' })[tone()];
  return (
    <div
      role={props.live ? (tone() === 'error' ? 'alert' : 'status') : undefined}
      class={cx(
        'flex gap-3 rounded-2xl border-2 p-4 text-base',
        tone() === 'error' && 'border-danger bg-danger/10',
        tone() === 'warning' && 'border-danger/60 bg-surface',
        tone() === 'success' && 'border-lime bg-lime/10',
        tone() === 'info' && 'border-line bg-surface',
        props.class,
      )}
    >
      <span
        aria-hidden="true"
        class={cx(
          'mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-sm font-black',
          tone() === 'success'
            ? 'bg-lime text-ink'
            : tone() === 'info'
              ? 'bg-surface-2 text-cream'
              : 'bg-danger text-ink',
        )}
      >
        {icon()}
      </span>
      <div class="min-w-0 flex-1">
        <Show when={props.title}>
          <p class="font-bold">{props.title}</p>
        </Show>
        <div class="text-cream/90">{props.children}</div>
      </div>
    </div>
  );
}

export function Badge(props: ParentProps<{ tone?: 'lime' | 'muted' | 'danger' }>) {
  return (
    <span
      class={cx(
        'inline-flex items-center gap-1 rounded-full px-3 py-1 text-sm font-bold whitespace-nowrap',
        props.tone === 'lime' && 'bg-lime text-ink',
        props.tone === 'danger' && 'bg-danger text-ink',
        (!props.tone || props.tone === 'muted') && 'bg-surface-2 text-muted',
      )}
    >
      {props.children}
    </span>
  );
}

// ---------------------------------------------------------------- Inputs

export function Toggle(props: {
  label: string;
  description?: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  const id = createUniqueId();
  return (
    <div class="flex min-h-14 items-center justify-between gap-4 py-2">
      <div class="min-w-0">
        <label for={id} class="text-lg font-bold">
          {props.label}
        </label>
        <Show when={props.description}>
          <p id={`${id}-d`} class="text-muted">
            {props.description}
          </p>
        </Show>
      </div>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={props.checked}
        aria-describedby={props.description ? `${id}-d` : undefined}
        onClick={() => props.onChange(!props.checked)}
        class={cx(
          'relative inline-flex h-12 w-20 shrink-0 items-center rounded-full border-2 transition-colors',
          props.checked ? 'border-lime bg-lime' : 'border-line bg-surface-2',
        )}
      >
        <span
          aria-hidden="true"
          class={cx(
            'absolute h-8 w-8 rounded-full transition-transform',
            props.checked ? 'translate-x-9 bg-ink' : 'translate-x-1.5 bg-muted',
          )}
        />
        <span class="sr-only">{props.checked ? 'On' : 'Off'}</span>
      </button>
    </div>
  );
}

export function Segmented<T extends string | number>(props: {
  label: string;
  options: readonly { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
}) {
  const id = createUniqueId();
  return (
    <fieldset>
      <legend id={id} class="mb-2 text-lg font-bold">
        {props.label}
      </legend>
      <div class="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {props.options.map((o) => (
          <button
            type="button"
            aria-pressed={props.value === o.value}
            onClick={() => props.onChange(o.value)}
            class={cx(
              'min-h-12 rounded-xl border-2 px-3 font-bold transition-colors',
              props.value === o.value
                ? 'border-lime bg-lime text-ink'
                : 'border-line bg-surface-2 text-cream',
            )}
          >
            {o.label}
          </button>
        ))}
      </div>
    </fieldset>
  );
}

/** A real radio input styled as a large tappable card (label provides the accessible name). */
export function RadioCard(
  props: ParentProps<{
    name: string;
    checked: boolean;
    onSelect: (event: MouseEvent) => void;
    class?: string;
  }>,
) {
  return (
    <label
      class={cx(
        'relative cursor-pointer has-[:focus-visible]:outline-[3px] has-[:focus-visible]:outline-offset-[3px] has-[:focus-visible]:outline-lime has-[:focus-visible]:outline-solid',
        props.class,
      )}
    >
      <input
        type="radio"
        class="absolute inset-0 z-10 m-0 h-full w-full cursor-pointer opacity-0"
        name={props.name}
        checked={props.checked}
        onClick={(e) => props.onSelect(e)}
      />
      {props.children}
    </label>
  );
}

// ---------------------------------------------------------------- Dialog

/**
 * Accessible modal: focus moves in, Tab is trapped, Escape and the Android back
 * button (via the controller) close it, and focus returns to the opener.
 */
export function Dialog(
  props: ParentProps<{ title: string; onClose: () => void; footer?: JSX.Element; wide?: boolean }>,
) {
  const titleId = createUniqueId();
  let panel: HTMLDivElement | undefined;
  const opener = document.activeElement as HTMLElement | null;

  const focusables = () =>
    panel
      ? Array.from(
          panel.querySelectorAll<HTMLElement>(
            'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])',
          ),
        )
      : [];

  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      props.onClose();
    } else if (e.key === 'Tab') {
      const items = focusables();
      if (items.length === 0) return;
      const first = items[0] as HTMLElement;
      const last = items[items.length - 1] as HTMLElement;
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
  };

  onMount(() => {
    document.addEventListener('keydown', onKey);
    (panel?.querySelector<HTMLElement>('[data-autofocus]') ?? panel)?.focus();
  });
  onCleanup(() => {
    document.removeEventListener('keydown', onKey);
    if (opener?.isConnected) opener.focus();
  });

  return (
    <div class="fixed inset-0 z-50 flex items-end justify-center bg-black/70 p-0 sm:items-center sm:p-4">
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        class={cx(
          'animate-pop safe-area max-h-[92dvh] w-full overflow-y-auto rounded-t-[2rem] bg-surface outline-none sm:rounded-[2rem]',
          props.wide ? 'sm:max-w-xl' : 'sm:max-w-md',
        )}
      >
        <div class="flex items-start justify-between gap-3 pt-2">
          <h2 id={titleId} class="text-2xl font-black tracking-tight">
            {props.title}
          </h2>
          <button
            type="button"
            onClick={() => props.onClose()}
            aria-label="Close"
            class="-mr-2 inline-flex min-h-12 min-w-12 shrink-0 items-center justify-center rounded-xl text-2xl hover:bg-surface-2"
          >
            <span aria-hidden="true">×</span>
          </button>
        </div>
        <div class="mt-3 space-y-4">{props.children}</div>
        <Show when={props.footer}>
          <div class="mt-6 flex flex-col gap-3">{props.footer}</div>
        </Show>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Brand

/** Original mark: a speech bubble wearing a secret-identity eye mask. */
export function Logo(props: { size?: number; class?: string }) {
  return (
    <svg
      width={props.size ?? 64}
      height={props.size ?? 64}
      viewBox="0 0 64 64"
      class={props.class}
      aria-hidden="true"
    >
      <path
        d="M12 10h40a8 8 0 0 1 8 8v22a8 8 0 0 1-8 8H31l-13 10v-10h-6a8 8 0 0 1-8-8V18a8 8 0 0 1 8-8z"
        fill="#C7FF5E"
      />
      <path d="M12 26c6-6 13-6 17 0 4-6 11-6 17 0 0 6-7 10-17 5-10 5-17 1-17-5z" fill="#101216" />
      <circle cx="20.5" cy="27.5" r="3" fill="#C7FF5E" />
      <circle cx="37.5" cy="27.5" r="3" fill="#C7FF5E" />
    </svg>
  );
}

/** Runs `fn` (untracked) whenever `source` changes after mount. */
export function useOnChange<T>(source: () => T, fn: () => void) {
  createEffect(on(source, () => fn(), { defer: true }));
}

export { cx };
