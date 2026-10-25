import { createMemo, createSignal, For, Index, Show } from 'solid-js';
import { useApp } from '../../app/context';
import { Badge, Button, Notice, RadioCard, Screen, Segmented } from '../../components/ui';
import { PACKS } from '../../content';
import {
  MAX_NAME_LENGTH,
  MAX_PLAYERS,
  MIN_PLAYERS,
  type NameError,
  validateRoster,
} from '../../domain/game/roster';
import type { TimerChoice } from '../../domain/game/types';

const NAME_ERRORS: Record<NameError, string> = {
  blank: 'Enter a name.',
  'too-long': `Keep it to ${MAX_NAME_LENGTH} characters.`,
  duplicate: 'Two players can’t share a name.',
};

const TIMER_OPTIONS: { value: string; label: string }[] = [
  { value: '60', label: '60 s' },
  { value: '90', label: '90 s' },
  { value: '120', label: '120 s' },
  { value: 'off', label: 'Off' },
];

export function SetupScreen() {
  const app = useApp();
  const [attempted, setAttempted] = createSignal(false);
  const [starting, setStarting] = createSignal(false);
  const players = () => app.setup().players;
  const validation = createMemo(() => validateRoster(players()));
  const packLocked = () => !app.packUnlocked(app.setup().packId);

  const start = async () => {
    setAttempted(true);
    if (!validation().ok || packLocked() || starting()) return;
    setStarting(true);
    try {
      await app.startFromSetup();
    } finally {
      setStarting(false);
    }
  };

  const timerValue = () =>
    app.setup().timerSeconds === null ? 'off' : String(app.setup().timerSeconds);
  const setTimer = (v: string) =>
    app.updateSetup({ timerSeconds: (v === 'off' ? null : Number(v)) as TimerChoice });

  return (
    <Screen
      title="Game setup"
      onBack={() => app.handleBack()}
      footer={
        <div class="space-y-2">
          <Show when={attempted() && !validation().ok}>
            <p role="alert" class="text-center font-semibold text-danger">
              Fix the highlighted names to start.
            </p>
          </Show>
          <Button
            size="xl"
            block
            busy={starting()}
            disabled={starting() || packLocked()}
            onClick={start}
          >
            Start game
          </Button>
        </div>
      }
    >
      <section aria-labelledby="players-h" class="space-y-3">
        <div class="flex items-baseline justify-between">
          <h2 id="players-h" class="text-xl font-black uppercase">
            Players
          </h2>
          <span class="text-muted">
            {players().length} / {MAX_PLAYERS}
          </span>
        </div>
        <ol class="space-y-2">
          <Index each={players()}>
            {(player, idx) => {
              const i = () => idx;
              const error = () => validation().nameErrors[player().id];
              const errorId = () => `err-${player().id}`;
              return (
                <li>
                  <div class="flex items-center gap-2">
                    <span aria-hidden="true" class="w-6 text-right font-black text-muted">
                      {i() + 1}
                    </span>
                    <input
                      class={`min-h-14 min-w-0 flex-1 rounded-2xl border-2 bg-surface px-4 text-lg font-semibold text-cream placeholder:text-muted ${
                        error() && (attempted() || error() !== 'blank')
                          ? 'border-danger'
                          : 'border-line focus:border-lime'
                      }`}
                      value={player().name}
                      maxLength={48}
                      autocomplete="off"
                      autocapitalize="words"
                      enterkeyhint="next"
                      aria-label={`Player ${i() + 1} name`}
                      aria-invalid={error() ? 'true' : undefined}
                      aria-describedby={error() ? errorId() : undefined}
                      onInput={(e) => app.setPlayerName(player().id, e.currentTarget.value)}
                    />
                    <button
                      type="button"
                      class="inline-flex min-h-12 min-w-12 items-center justify-center rounded-xl text-2xl text-muted hover:bg-surface-2 hover:text-cream disabled:opacity-30"
                      aria-label={`Remove ${player().name.trim() || `player ${i() + 1}`}`}
                      disabled={players().length <= MIN_PLAYERS}
                      onClick={() => app.removePlayer(player().id)}
                    >
                      <span aria-hidden="true">−</span>
                    </button>
                  </div>
                  <Show when={error() && (attempted() || error() !== 'blank')}>
                    <p id={errorId()} class="mt-1 ml-8 font-semibold text-danger">
                      {NAME_ERRORS[error() as NameError]}
                    </p>
                  </Show>
                </li>
              );
            }}
          </Index>
        </ol>
        <Button
          variant="secondary"
          block
          disabled={players().length >= MAX_PLAYERS}
          onClick={() => app.addPlayer()}
        >
          + Add player
        </Button>
        <Show when={players().length >= MAX_PLAYERS}>
          <p class="text-center text-muted">That’s the maximum of {MAX_PLAYERS} players.</p>
        </Show>
      </section>

      <section aria-labelledby="pack-h" class="mt-8 space-y-3">
        <h2 id="pack-h" class="text-xl font-black uppercase">
          Pack
        </h2>
        <div class="grid gap-2" role="radiogroup" aria-labelledby="pack-h">
          <For each={PACKS}>
            {(pack) => {
              const unlocked = () => app.packUnlocked(pack.id);
              const selected = () => app.setup().packId === pack.id;
              return (
                <RadioCard
                  name="pack"
                  checked={selected()}
                  onSelect={(e) => {
                    if (unlocked()) {
                      app.selectPack(pack.id);
                    } else {
                      e.preventDefault(); // locked packs are never selected; show the paywall
                      app.openPaywall(pack.id);
                    }
                  }}
                  class={`flex min-h-16 flex-wrap items-center justify-between gap-x-3 gap-y-2 rounded-2xl border-2 px-4 py-3 text-left transition-colors ${
                    selected()
                      ? 'border-lime bg-lime/10'
                      : 'border-line bg-surface hover:border-muted'
                  }`}
                >
                  <span class="min-w-0">
                    <span class="block text-lg font-extrabold">{pack.name}</span>
                    <span class="block text-muted">{pack.entries.length} words</span>
                  </span>
                  <Show when={unlocked()} fallback={<Badge>🔒 Preview</Badge>}>
                    <Show
                      when={selected()}
                      fallback={<Badge>{pack.tier === 'free' ? 'Free' : 'Owned'}</Badge>}
                    >
                      <Badge tone="lime">✓ Selected</Badge>
                    </Show>
                  </Show>
                </RadioCard>
              );
            }}
          </For>
        </div>
        <Show when={packLocked()}>
          <Notice tone="warning">
            That pack is no longer unlocked on this device. Choose another pack.
          </Notice>
        </Show>
      </section>

      <section class="mt-8">
        <Segmented
          label="Discussion timer"
          options={TIMER_OPTIONS}
          value={timerValue()}
          onChange={setTimer}
        />
      </section>
    </Screen>
  );
}
