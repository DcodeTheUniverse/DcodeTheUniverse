import { Show } from 'solid-js';
import { brand } from '../../app/config';
import { useApp } from '../../app/context';
import { Button, Card, Logo, Screen } from '../../components/ui';
import { hasActiveRound } from '../../domain/game/machine';

export function HomeScreen() {
  const app = useApp();
  const active = () => hasActiveRound(app.session());
  const scored = () => {
    const s = app.session();
    return s && !hasActiveRound(s) && s.roundsCompleted > 0 ? s : null;
  };

  const newGame = () => {
    if (active()) {
      app.confirmAbandon();
      return;
    }
    app.navigate('setup');
  };

  return (
    <Screen labelledBy="home-title">
      <div class="flex flex-1 flex-col justify-center gap-8 py-6">
        <div class="animate-pop text-center">
          <Logo size={96} class="mx-auto drop-shadow-[0_6px_0_#0a0b0e]" />
          <h1
            id="home-title"
            class="mt-4 text-5xl leading-none font-black tracking-tighter uppercase sm:text-6xl"
          >
            Bluff <span class="text-lime">Club</span>
          </h1>
          <p class="mt-3 text-xl font-semibold text-cream">{brand.tagline}</p>
          <p class="mx-auto mt-3 max-w-sm text-lg text-muted">
            A pass-the-phone party game for {brand.minPlayers}–{brand.maxPlayers} players in the
            same room. One phone, no accounts, no internet needed to play.
          </p>
        </div>

        <div class="flex flex-col gap-3">
          <Show
            when={active()}
            fallback={
              <Button size="xl" block onClick={() => app.navigate('setup')}>
                {scored() ? 'Continue session' : 'Play'}
              </Button>
            }
          >
            <Button size="xl" block onClick={() => app.navigate('game')}>
              Resume round
            </Button>
            <Button variant="secondary" block onClick={newGame}>
              Abandon round
            </Button>
          </Show>
          <Show when={scored()}>
            {(s) => (
              <p class="text-center text-muted">
                {s().roundsCompleted} round{s().roundsCompleted === 1 ? '' : 's'} played this
                session.{' '}
                <button
                  type="button"
                  class="min-h-12 font-bold text-cream underline underline-offset-4"
                  onClick={() => app.confirmEndSession()}
                >
                  End session
                </button>
              </p>
            )}
          </Show>
          <div class="grid grid-cols-2 gap-3">
            <Button variant="secondary" onClick={() => app.navigate('packs')}>
              Packs
            </Button>
            <Button variant="secondary" onClick={() => app.navigate('settings')}>
              Settings
            </Button>
          </div>
        </div>

        <Show when={!app.settings().tutorialSeen}>
          <Card tone="outline" class="text-center">
            <p class="text-lg font-bold">New to Bluff Club?</p>
            <p class="text-muted">Three quick cards explain everything.</p>
            <Button variant="ghost" class="mt-1" onClick={() => app.navigate('tutorial')}>
              How to play →
            </Button>
          </Card>
        </Show>
      </div>
    </Screen>
  );
}
