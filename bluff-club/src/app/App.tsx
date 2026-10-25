import { For, Match, onCleanup, onMount, Show, Switch } from 'solid-js';
import { Button, Dialog, Notice, Spinner } from '../components/ui';
import { GameScreen } from '../features/game/GameScreen';
import { HomeScreen } from '../features/home/HomeScreen';
import { PacksScreen, Paywall } from '../features/packs/PacksScreen';
import { SettingsScreen } from '../features/settings/SettingsScreen';
import { SetupScreen } from '../features/setup/SetupScreen';
import { TutorialScreen } from '../features/tutorial/TutorialScreen';
import { AppContext } from './context';
import type { AppController } from './controller';

export function App(props: { controller: AppController }) {
  const app = props.controller;
  onMount(() => {
    void app.init();
  });
  onCleanup(() => app.dispose());

  return (
    <AppContext.Provider value={app}>
      <Show
        when={app.ready()}
        fallback={
          <div class="club-bg flex min-h-dvh items-center justify-center text-muted">
            <Spinner label="Opening the club…" />
          </div>
        }
      >
        <Show when={app.notices().length > 0 || app.storageFailed()}>
          <div
            class="mx-auto max-w-xl space-y-2 px-4"
            style={{ 'padding-top': 'max(var(--sa-top), 0.75rem)' }}
          >
            <Show when={app.storageFailed()}>
              <Notice tone="warning" live title="Couldn’t save progress">
                The game keeps working, but progress may not survive if the app is closed.
              </Notice>
            </Show>
            <For each={app.notices()}>
              {(n) => (
                <Notice tone={n.tone} live>
                  <div class="flex items-start justify-between gap-2">
                    <span>{n.text}</span>
                    <Button
                      variant="ghost"
                      size="md"
                      class="-my-2 -mr-2 shrink-0"
                      onClick={() => app.dismissNotice(n.id)}
                    >
                      OK
                    </Button>
                  </div>
                </Notice>
              )}
            </For>
          </div>
        </Show>

        <Switch>
          <Match when={app.screen() === 'home'}>
            <HomeScreen />
          </Match>
          <Match when={app.screen() === 'setup'}>
            <SetupScreen />
          </Match>
          <Match when={app.screen() === 'game'}>
            <GameScreen />
          </Match>
          <Match when={app.screen() === 'packs'}>
            <PacksScreen />
          </Match>
          <Match when={app.screen() === 'settings'}>
            <SettingsScreen />
          </Match>
          <Match when={app.screen() === 'tutorial'}>
            <TutorialScreen />
          </Match>
        </Switch>

        <ModalHost />
      </Show>
    </AppContext.Provider>
  );

  function ModalHost() {
    return (
      <Show when={app.modal()} keyed>
        {(modal) => (
          <Switch>
            <Match when={modal.kind === 'confirm' && modal} keyed>
              {(m) => (
                <Dialog
                  title={m.title}
                  onClose={() => app.setModal(null)}
                  footer={
                    <>
                      <Button
                        variant={m.danger ? 'danger' : 'primary'}
                        block
                        onClick={() => void m.onConfirm()}
                      >
                        {m.confirmLabel}
                      </Button>
                      <Button
                        variant="secondary"
                        block
                        data-autofocus
                        onClick={() => app.setModal(null)}
                      >
                        {m.cancelLabel ?? 'Cancel'}
                      </Button>
                    </>
                  }
                >
                  <p class="text-lg text-cream/90">{m.body}</p>
                </Dialog>
              )}
            </Match>
            <Match when={modal.kind === 'paywall' && modal} keyed>
              {(m) => <Paywall target={m.target} />}
            </Match>
          </Switch>
        )}
      </Show>
    );
  }
}
