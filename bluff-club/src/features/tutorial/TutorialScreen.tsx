import { createSignal, For, Show } from 'solid-js';
import { useApp } from '../../app/context';
import { Button, Card, Screen } from '../../components/ui';

export const TUTORIAL_CARDS = [
  {
    title: 'Everyone knows…',
    body: 'Pass the phone around. Each player privately peeks at their card. Everyone sees the same secret word — except one player.',
    art: '🤫',
  },
  {
    title: '…someone’s pretending',
    body: 'The bluffer gets no word. Taking turns, everyone says one short clue about the word — never the word itself. The bluffer has to fake it.',
    art: '🎭',
  },
  {
    title: 'Vote them out',
    body: 'Discuss, then vote privately for the bluffer. Catch them and everyone else scores 1 point. Pick wrong — or tie twice — and the bluffer scores 2.',
    art: '🗳️',
  },
] as const;

export function TutorialScreen() {
  const app = useApp();
  const [index, setIndex] = createSignal(0);
  const card = () => TUTORIAL_CARDS[index()] ?? TUTORIAL_CARDS[0];
  const last = () => index() === TUTORIAL_CARDS.length - 1;

  const finish = () => {
    app.updateSettings({ tutorialSeen: true });
    app.handleBack();
  };

  return (
    <Screen
      title="How to play"
      onBack={finish}
      actions={
        <Button variant="ghost" size="md" onClick={finish}>
          Skip
        </Button>
      }
      footer={
        <div class="flex gap-3">
          <Show when={index() > 0}>
            <Button variant="secondary" class="flex-1" onClick={() => setIndex((i) => i - 1)}>
              Back
            </Button>
          </Show>
          <Button class="flex-[2]" onClick={() => (last() ? finish() : setIndex((i) => i + 1))}>
            {last() ? 'Got it' : 'Next'}
          </Button>
        </div>
      }
    >
      <div class="flex flex-1 flex-col justify-center">
        <Show when={card()} keyed>
          {(c) => (
            <Card tone="raised" class="animate-pop py-10 text-center">
              <div aria-hidden="true" class="text-7xl">
                {c.art}
              </div>
              <h2 class="mt-6 text-3xl font-black tracking-tight">{c.title}</h2>
              <p class="mt-3 text-lg text-cream/90">{c.body}</p>
            </Card>
          )}
        </Show>
        <p class="mt-6 text-center text-muted" aria-live="polite">
          Card {index() + 1} of {TUTORIAL_CARDS.length}
        </p>
        <div class="mt-2 flex justify-center gap-2" aria-hidden="true">
          <For each={TUTORIAL_CARDS}>
            {(_, i) => (
              <span class={`h-2.5 w-2.5 rounded-full ${i() === index() ? 'bg-lime' : 'bg-line'}`} />
            )}
          </For>
        </div>
      </div>
    </Screen>
  );
}
