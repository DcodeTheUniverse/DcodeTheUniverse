import { createMemo, createSignal, For, Match, Show, Switch } from 'solid-js';
import { useApp } from '../../app/context';
import { Badge, Button, Card, cx, RadioCard, Screen, useOnChange } from '../../components/ui';
import { getPack } from '../../content';
import { standings } from '../../domain/game/machine';
import type { Player } from '../../domain/game/roster';
import type {
  CluesPhase,
  DiscussionPhase,
  HandoverPhase,
  PausedPhase,
  ResultsPhase,
  RevealPhase,
  Round,
  RunoffIntroPhase,
  Session,
  Tally,
  VoteHandoverPhase,
  VotePhase,
} from '../../domain/game/types';
import { SecretCard } from './SecretCard';
import { TimerDisplay } from './Timer';

export function GameScreen() {
  const app = useApp();
  const session = () => app.session();
  const round = () => session()?.round ?? null;
  const phase = () => round()?.phase;

  const packName = () => getPack(round()?.packId ?? '')?.name ?? 'Unknown pack';
  const canPause = () => {
    const k = phase()?.kind;
    return !!k && k !== 'paused' && k !== 'results';
  };

  return (
    <Show
      when={session() && round()}
      fallback={
        <Screen title="No round in progress" onBack={() => app.goHome()}>
          <Card>
            <p class="text-lg">There’s no active round. Start a new game from setup.</p>
            <Button class="mt-4" block onClick={() => app.navigate('setup', { replace: true })}>
              Set up a game
            </Button>
          </Card>
        </Screen>
      }
    >
      <Screen labelledBy="game-heading">
        <div class="mb-4 flex min-h-12 items-center justify-between gap-2">
          <p class="font-bold text-muted">
            Round {round()?.number} · {packName()}
          </p>
          <Show when={canPause()}>
            <Button
              variant="secondary"
              size="md"
              onClick={() => app.interrupt()}
              aria-label="Pause round"
            >
              ❚❚ Pause
            </Button>
          </Show>
        </div>
        <Switch>
          <Match when={phase()?.kind === 'handover' && (phase() as HandoverPhase)} keyed>
            {(p) => <HandoverView phase={p} />}
          </Match>
          <Match when={phase()?.kind === 'reveal' && (phase() as RevealPhase)} keyed>
            {(p) => <RevealView phase={p} packName={packName()} />}
          </Match>
          <Match when={phase()?.kind === 'clues' && (phase() as CluesPhase)}>
            <CluesView />
          </Match>
          <Match when={phase()?.kind === 'discussion' && (phase() as DiscussionPhase)}>
            {(p) => <DiscussionView phase={p()} />}
          </Match>
          <Match when={phase()?.kind === 'vote-handover' && (phase() as VoteHandoverPhase)} keyed>
            {(p) => <VoteHandoverView phase={p} />}
          </Match>
          <Match when={phase()?.kind === 'vote' && (phase() as VotePhase)} keyed>
            {(p) => <VoteView phase={p} />}
          </Match>
          <Match when={phase()?.kind === 'runoff-intro' && (phase() as RunoffIntroPhase)}>
            {(p) => <RunoffView phase={p()} />}
          </Match>
          <Match when={phase()?.kind === 'paused' && (phase() as PausedPhase)}>
            {(p) => <PausedView phase={p()} />}
          </Match>
          <Match when={phase()?.kind === 'results' && (phase() as ResultsPhase)}>
            {(p) => <ResultsView phase={p()} />}
          </Match>
        </Switch>
      </Screen>
    </Show>
  );

  // ---------------------------------------------------------------- views

  function playerAt(index: number): Player | undefined {
    return session()?.players[index];
  }

  function nameOf(id: string | null): string {
    return session()?.players.find((p) => p.id === id)?.name ?? '—';
  }

  function HandoverView(props: { phase: HandoverPhase }) {
    const player = () => playerAt(props.phase.index);
    return (
      <div class="flex flex-1 flex-col justify-between gap-6">
        <div class="animate-pop text-center">
          <p class="text-lg font-bold text-muted">
            Card {props.phase.index + 1} of {session()?.players.length}
          </p>
          <h2
            id="game-heading"
            class="mt-6 text-4xl leading-tight font-black tracking-tight break-words"
          >
            Pass the phone to <span class="text-lime">{player()?.name}</span>.
          </h2>
          <p class="mt-4 text-lg text-muted">Everyone else, look away.</p>
        </div>
        <div class="space-y-3">
          <Button size="xl" block onClick={() => void app.openCard()}>
            I’m {player()?.name} — open my card
          </Button>
          <p class="text-center text-sm text-muted">
            Using a screen reader? It may read your card aloud — use headphones or turn the volume
            down.
          </p>
        </div>
      </div>
    );
  }

  function RevealView(props: { phase: RevealPhase; packName: string }) {
    const [seen, setSeen] = createSignal(false);
    const [visible, setVisible] = createSignal(false);
    const player = () => playerAt(props.phase.index);
    const last = () => props.phase.index === (session()?.players.length ?? 0) - 1;
    const secret = () => {
      const r = round() as Round;
      return player()?.id === r.blufferId
        ? ({ kind: 'bluffer' } as const)
        : ({ kind: 'word', word: r.word } as const);
    };
    return (
      <div class="flex flex-1 flex-col gap-5">
        <h2 id="game-heading" class="text-center text-2xl font-black break-words">
          {player()?.name}’s card
        </h2>
        <SecretCard
          packName={props.packName}
          secret={secret}
          onSeen={() => setSeen(true)}
          onVisibleChange={setVisible}
        />
        <div class="mt-auto space-y-2">
          <Button
            size="xl"
            variant="secondary"
            block
            disabled={!seen() || visible()}
            onClick={() => {
              if (app.dispatch({ type: 'FINISH_REVEAL' })) app.feedback('tap');
            }}
          >
            {last() ? 'Done — everyone’s seen their card' : 'Done — pass the phone'}
          </Button>
          <p class="text-center text-muted" aria-live="polite">
            {visible()
              ? 'Cover your card before passing the phone.'
              : seen()
                ? 'You can peek again before you finish.'
                : 'Look at your card first.'}
          </p>
        </div>
      </div>
    );
  }

  function ClueOrder() {
    const order = () => {
      const s = session() as Session;
      return (round() as Round).clueOrder.map(
        (id) => s.players.find((p) => p.id === id)?.name ?? '',
      );
    };
    return (
      <ol class="grid gap-2">
        <For each={order()}>
          {(name, i) => (
            <li
              class={cx(
                'flex items-center gap-3 rounded-2xl px-4 py-3',
                i() === 0 ? 'bg-lime text-ink' : 'bg-surface',
              )}
            >
              <span class="w-6 text-right font-black">{i() + 1}</span>
              <span class="min-w-0 text-lg font-bold break-words">{name}</span>
              <Show when={i() === 0}>
                <span class="ml-auto text-sm font-black uppercase">Starts</span>
              </Show>
            </li>
          )}
        </For>
      </ol>
    );
  }

  function RulesReminder() {
    return (
      <p class="text-muted">
        One short clue each, in order. Never say the word itself or an obvious part of it. Then talk
        it out.
      </p>
    );
  }

  function CluesView() {
    return (
      <div class="flex flex-1 flex-col gap-5">
        <div class="text-center">
          <h2 id="game-heading" class="text-3xl font-black tracking-tight">
            Everyone has seen their card
          </h2>
          <p class="mt-2 text-xl">
            <span class="font-black text-lime">{nameOf((round() as Round).startingPlayerId)}</span>{' '}
            gives the first clue.
          </p>
        </div>
        <ClueOrder />
        <RulesReminder />
        <Button
          size="xl"
          block
          class="mt-auto"
          onClick={() => app.dispatch({ type: 'START_DISCUSSION' })}
        >
          Start discussion
        </Button>
      </div>
    );
  }

  function DiscussionView(props: { phase: DiscussionPhase }) {
    const running = () => props.phase.timer.runningSince !== null;
    return (
      <div class="flex flex-1 flex-col gap-5">
        <h2 id="game-heading" class="text-center text-3xl font-black tracking-tight">
          Clues & discussion
        </h2>
        <TimerDisplay
          timer={props.phase.timer}
          expiredText="Time’s up! Start voting when you’re ready."
        />
        <Show when={props.phase.timer.durationMs !== null}>
          <Button
            variant="secondary"
            block
            onClick={() => app.dispatch({ type: running() ? 'PAUSE_TIMER' : 'RESUME_TIMER' })}
          >
            {running() ? 'Pause timer' : 'Resume timer'}
          </Button>
        </Show>
        <details class="rounded-2xl bg-surface p-4">
          <summary class="min-h-8 cursor-pointer text-lg font-bold">Clue order & rules</summary>
          <div class="mt-3 space-y-3">
            <ClueOrder />
            <RulesReminder />
          </div>
        </details>
        <Button
          size="xl"
          block
          class="mt-auto"
          onClick={() => app.dispatch({ type: 'START_VOTING' })}
        >
          Start voting
        </Button>
      </div>
    );
  }

  function VoteHandoverView(props: { phase: VoteHandoverPhase }) {
    const voter = () => playerAt(props.phase.voterIndex);
    const runoff = () => props.phase.ballot.kind === 'runoff';
    return (
      <div class="flex flex-1 flex-col justify-between gap-6">
        <div class="animate-pop text-center">
          <Badge tone={runoff() ? 'danger' : 'muted'}>
            {runoff() ? 'Runoff vote' : 'Secret vote'}
          </Badge>
          <p class="mt-3 text-lg font-bold text-muted">
            Voter {props.phase.voterIndex + 1} of {session()?.players.length}
          </p>
          <h2
            id="game-heading"
            class="mt-6 text-4xl leading-tight font-black tracking-tight break-words"
          >
            Pass the phone to <span class="text-lime">{voter()?.name}</span>.
          </h2>
          <p class="mt-4 text-lg text-muted">Vote privately. Nobody will see who voted for whom.</p>
        </div>
        <Button size="xl" block onClick={() => app.dispatch({ type: 'OPEN_BALLOT' })}>
          I’m {voter()?.name} — vote privately
        </Button>
      </div>
    );
  }

  function VoteView(props: { phase: VotePhase }) {
    const [choice, setChoice] = createSignal<string | null>(null);
    const [submitting, setSubmitting] = createSignal(false);
    const voter = () => playerAt(props.phase.voterIndex);
    const candidates = createMemo(() =>
      props.phase.ballot.candidates
        .filter((id) => id !== voter()?.id)
        .map((id) => ({ id, name: nameOf(id) })),
    );
    // Any outside interruption (blur, background) clears the unsubmitted selection.
    useOnChange(app.coverSignal, () => setChoice(null));

    const submit = () => {
      const target = choice();
      const v = voter();
      if (!target || !v || submitting()) return;
      setSubmitting(true);
      setChoice(null); // hide the selection immediately
      if (app.dispatch({ type: 'CAST_VOTE', voterId: v.id, targetId: target })) app.feedback('tap');
      else setSubmitting(false);
    };

    return (
      <div class="flex flex-1 flex-col gap-4">
        <h2 id="game-heading" class="text-center text-2xl font-black break-words">
          {voter()?.name}, who is the bluffer?
        </h2>
        <Show when={props.phase.ballot.kind === 'runoff'}>
          <p class="text-center text-muted">Runoff: choose between the tied players.</p>
        </Show>
        <div role="radiogroup" aria-labelledby="game-heading" class="grid gap-2">
          <For each={candidates()}>
            {(c) => (
              <RadioCard
                name="vote"
                checked={choice() === c.id}
                onSelect={() => setChoice(c.id)}
                class={cx(
                  'flex min-h-16 items-center justify-between gap-3 rounded-2xl border-2 px-4 text-left text-xl font-extrabold',
                  choice() === c.id ? 'border-lime bg-lime text-ink' : 'border-line bg-surface',
                )}
              >
                <span class="min-w-0 break-words">{c.name}</span>
                <Show when={choice() === c.id}>
                  <span aria-hidden="true">✓</span>
                </Show>
              </RadioCard>
            )}
          </For>
        </div>
        <p class="text-center text-sm text-muted">You can’t vote for yourself.</p>
        <Button
          size="xl"
          block
          class="mt-auto"
          disabled={!choice() || submitting()}
          onClick={submit}
        >
          Lock in my vote
        </Button>
      </div>
    );
  }

  function RunoffView(props: { phase: RunoffIntroPhase }) {
    const names = () => props.phase.candidates.map((id) => nameOf(id));
    const votes = () => props.phase.initialTally[props.phase.candidates[0] ?? ''] ?? 0;
    const started = () => props.phase.timer.started;
    const running = () => props.phase.timer.runningSince !== null;
    return (
      <div class="flex flex-1 flex-col gap-5">
        <div class="text-center">
          <Badge tone="danger">It’s a tie</Badge>
          <h2 id="game-heading" class="mt-3 text-3xl font-black tracking-tight break-words">
            {names().join(' vs ')}
          </h2>
          <p class="mt-2 text-lg text-muted">
            Tied on {votes()} vote{votes() === 1 ? '' : 's'} each.
          </p>
        </div>
        <Card>
          <h3 class="text-lg font-black">How the runoff works</h3>
          <ol class="mt-2 list-decimal space-y-1 pl-5 text-cream/90">
            <li>Take 20 more seconds to argue it out.</li>
            <li>Everyone votes again, privately — only for {names().join(' or ')}.</li>
            <li>Still tied? The group failed to agree, so the bluffer wins.</li>
          </ol>
        </Card>
        <Show
          when={started()}
          fallback={
            <Button variant="secondary" block onClick={() => app.dispatch({ type: 'START_TIMER' })}>
              Start 20-second discussion
            </Button>
          }
        >
          <TimerDisplay timer={props.phase.timer} expiredText="Time’s up! Start the runoff vote." />
          <Button
            variant="secondary"
            block
            onClick={() => app.dispatch({ type: running() ? 'PAUSE_TIMER' : 'RESUME_TIMER' })}
          >
            {running() ? 'Pause timer' : 'Resume timer'}
          </Button>
        </Show>
        <Button
          size="xl"
          block
          class="mt-auto"
          onClick={() => app.dispatch({ type: 'START_RUNOFF_VOTE' })}
        >
          Start runoff vote
        </Button>
      </div>
    );
  }

  function PausedView(props: { phase: PausedPhase }) {
    const recovered = () => props.phase.reason === 'recovered';
    return (
      <div class="flex flex-1 flex-col justify-center gap-6">
        <div class="text-center">
          <div aria-hidden="true" class="text-6xl">
            ⏸
          </div>
          <h2 id="game-heading" class="mt-4 text-4xl font-black tracking-tight">
            {recovered() ? 'Welcome back' : 'Round paused'}
          </h2>
          <p class="mt-3 text-lg text-muted">
            {recovered()
              ? 'We restored your round. All cards are covered and the timer is paused.'
              : 'All cards are covered and the timer is paused.'}
          </p>
          <p class="mt-2 text-lg text-muted">
            Make sure the right person is holding the phone, then resume.
          </p>
        </div>
        <div class="space-y-3">
          <Button size="xl" block onClick={() => app.dispatch({ type: 'RESUME' })}>
            Resume round
          </Button>
          <Button variant="secondary" block onClick={() => app.goHome()}>
            Go to home screen (keeps round)
          </Button>
          <Button variant="ghost" block onClick={() => app.confirmAbandon()}>
            Abandon round
          </Button>
        </div>
      </div>
    );
  }

  function TallyList(props: { title: string; tally: Tally }) {
    const rows = () =>
      Object.entries(props.tally)
        .map(([id, count]) => ({ id, count, name: nameOf(id) }))
        .sort((a, b) => b.count - a.count);
    const max = () => Math.max(1, ...rows().map((r) => r.count));
    return (
      <div>
        <h3 class="mb-2 font-black uppercase">{props.title}</h3>
        <ul class="space-y-2">
          <For each={rows()}>
            {(r) => (
              <li class="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3">
                <span class="min-w-0 font-bold break-words">{r.name}</span>
                <span class="font-black tabular-nums">
                  {r.count} vote{r.count === 1 ? '' : 's'}
                </span>
                <span
                  class="col-span-2 mt-1 h-2 overflow-hidden rounded-full bg-surface-2"
                  aria-hidden="true"
                >
                  <span
                    class="block h-full rounded-full bg-cream"
                    style={{ width: `${(r.count / max()) * 100}%` }}
                  />
                </span>
              </li>
            )}
          </For>
        </ul>
      </div>
    );
  }

  function ResultsView(props: { phase: ResultsPhase }) {
    const result = () => props.phase.result;
    const informedWon = () => result().outcome === 'informed-win';
    const [busy, setBusy] = createSignal(false);
    const headline = () => {
      switch (result().reason) {
        case 'caught':
          return 'Bluffer caught!';
        case 'wrong-accusation':
          return 'The bluffer got away!';
        case 'runoff-tie':
          return 'Deadlock — the bluffer wins!';
      }
    };
    const explanation = () => {
      switch (result().reason) {
        case 'caught':
          return `The group voted out ${nameOf(result().accusedId)}, who was the bluffer. Everyone else scores 1 point.`;
        case 'wrong-accusation':
          return `The group voted out ${nameOf(result().accusedId)}, who knew the word. The bluffer scores 2 points.`;
        case 'runoff-tie':
          return 'The runoff tied again, so the group failed to agree. The bluffer scores 2 points.';
      }
    };
    const table = () => standings(session() as Session);
    const playAgain = async () => {
      if (busy()) return;
      setBusy(true);
      try {
        await app.playAgain();
      } finally {
        setBusy(false);
      }
    };

    return (
      <div class="flex flex-1 flex-col gap-5">
        <Confetti />
        <Card
          tone="raised"
          class={cx(
            'animate-pop text-center',
            informedWon() ? 'bg-lime text-ink' : 'bg-danger text-ink',
          )}
        >
          <p class="text-sm font-black tracking-widest uppercase">
            {informedWon() ? 'Team wins' : 'Bluffer wins'}
          </p>
          <h2 id="game-heading" class="mt-1 text-4xl leading-tight font-black tracking-tight">
            {headline()}
          </h2>
          <p class="mt-2 text-lg font-semibold">{explanation()}</p>
        </Card>

        <div class="grid grid-cols-2 gap-3">
          <Card>
            <p class="text-sm font-bold text-muted uppercase">The word</p>
            <p class="mt-1 text-2xl font-black break-words">{result().word}</p>
          </Card>
          <Card>
            <p class="text-sm font-bold text-muted uppercase">The bluffer</p>
            <p class="mt-1 text-2xl font-black break-words">{nameOf(result().blufferId)}</p>
          </Card>
        </div>

        <Card class="space-y-5">
          <TallyList
            title={result().runoffTally ? 'First vote' : 'Votes'}
            tally={result().initialTally}
          />
          <Show when={result().runoffTally}>
            {(t) => <TallyList title="Runoff vote" tally={t()} />}
          </Show>
          <p class="text-sm text-muted">Totals only — individual votes stay secret.</p>
        </Card>

        <Card>
          <h3 class="mb-2 font-black uppercase">Session scores</h3>
          <ol class="space-y-1">
            <For each={table()}>
              {(row) => {
                const gained = () => result().pointsAwarded[row.player.id] ?? 0;
                return (
                  <li class="flex items-center justify-between gap-3 py-1">
                    <span class="min-w-0 font-bold break-words">{row.player.name}</span>
                    <span class="flex items-center gap-2 tabular-nums">
                      <Show when={gained() > 0}>
                        <Badge tone="lime">+{gained()}</Badge>
                      </Show>
                      <span class="w-8 text-right text-xl font-black">{row.score}</span>
                    </span>
                  </li>
                );
              }}
            </For>
          </ol>
        </Card>

        <div class="sticky bottom-0 space-y-3 bg-ink/95 pt-2 pb-1">
          <Button size="xl" block busy={busy()} disabled={busy()} onClick={playAgain}>
            Play again
          </Button>
          <div class="grid grid-cols-2 gap-3">
            <Button variant="secondary" onClick={() => app.changePack()}>
              Change pack
            </Button>
            <Button variant="secondary" onClick={() => app.confirmEndSession()}>
              End session
            </Button>
          </div>
        </div>
      </div>
    );
  }
}

/** Lightweight CSS confetti; hidden entirely under reduced motion. */
function Confetti() {
  const colors = ['#C7FF5E', '#FF6B6B', '#F6F5EF', '#ADB3C2'];
  const pieces = Array.from({ length: 28 }, (_, i) => ({
    left: (i * 37) % 100,
    delay: (i % 7) * 0.08,
    color: colors[i % colors.length],
  }));
  return (
    <div class="pointer-events-none fixed inset-0 z-30 overflow-hidden" aria-hidden="true">
      <For each={pieces}>
        {(p) => (
          <span
            class="confetti-piece"
            style={{ left: `${p.left}%`, 'animation-delay': `${p.delay}s`, background: p.color }}
          />
        )}
      </For>
    </div>
  );
}
