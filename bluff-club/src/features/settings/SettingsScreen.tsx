import { createSignal, Show } from 'solid-js';
import { appConfig, brand } from '../../app/config';
import { useApp } from '../../app/context';
import { Button, Card, Notice, Screen, Segmented, Toggle } from '../../components/ui';

export function SettingsScreen() {
  const app = useApp();
  const s = () => app.settings();
  const [exported, setExported] = createSignal<string | null>(null);
  const [resetDone, setResetDone] = createSignal<string | null>(null);

  const confirmReset = (kind: 'game' | 'all') => {
    app.setModal({
      kind: 'confirm',
      title: kind === 'game' ? 'Clear scores and saved round?' : 'Reset all app data?',
      body:
        kind === 'game'
          ? 'This clears session scores, any saved round and word history. Your players and settings stay. Purchases are not affected — resetting never cancels or refunds a purchase.'
          : 'This clears scores, any saved round, word history, players, settings and local playtest counters. Purchases are not affected — they stay with your Google Play account and can be restored.',
      confirmLabel: kind === 'game' ? 'Clear game data' : 'Reset everything',
      danger: true,
      onConfirm: async () => {
        app.setModal(null);
        if (kind === 'game') await app.resetGameData();
        else await app.resetEverything();
        setResetDone(kind === 'game' ? 'Game data cleared.' : 'App data reset.');
      },
    });
  };

  const exportStats = async () => {
    const json = app.metrics()?.exportJson(appConfig.version) ?? '{}';
    try {
      await navigator.clipboard.writeText(json);
      setExported('Copied to clipboard. Paste it into your feedback message.');
    } catch {
      setExported(json);
    }
  };

  const counters = () => app.metrics()?.snapshot().counters;

  return (
    <Screen title="Settings" onBack={() => app.handleBack()}>
      <div class="space-y-5">
        <Card>
          <h2 class="mb-1 text-lg font-black uppercase">Feel</h2>
          <Toggle
            label="Sound"
            description="Soft clicks and a chime when the timer ends."
            checked={s().sound}
            onChange={(v) => app.updateSettings({ sound: v })}
          />
          <Toggle
            label="Haptics"
            description="Light vibrations on supported phones."
            checked={s().haptics}
            onChange={(v) => app.updateSettings({ haptics: v })}
          />
          <div class="mt-3">
            <Segmented
              label="Revealing your card"
              options={[
                { value: 'hold', label: 'Hold to reveal' },
                { value: 'tap', label: 'Tap (auto-hides)' },
              ]}
              value={s().revealMode}
              onChange={(v) => app.updateSettings({ revealMode: v })}
            />
            <p class="mt-2 text-muted">Both options are always available on the card screen.</p>
          </div>
        </Card>

        <Card>
          <h2 class="text-lg font-black uppercase">Rules</h2>
          <ul class="mt-2 list-disc space-y-1 pl-5 text-cream/90">
            <li>3–10 players share one phone. One player is secretly the bluffer.</li>
            <li>Everyone else sees the same secret word. The bluffer sees no word.</li>
            <li>Each player gives one short clue — never the word or an obvious part of it.</li>
            <li>Discuss, then vote privately. You can’t vote for yourself.</li>
            <li>Most votes on the bluffer: everyone else +1. Otherwise the bluffer +2.</li>
            <li>
              A tie gets 20 seconds of discussion and one runoff. Tied again: the bluffer wins.
            </li>
          </ul>
          <Button variant="secondary" block class="mt-4" onClick={() => app.navigate('tutorial')}>
            Show the tutorial
          </Button>
        </Card>

        <Card>
          <h2 class="text-lg font-black uppercase">Purchases</h2>
          <p class="mt-1 text-muted">
            Packs are once-off purchases tied to your Google Play account. Restore them after
            reinstalling or on a new phone.
          </p>
          <Button
            variant="secondary"
            block
            class="mt-3"
            busy={app.restoring()}
            disabled={app.restoring()}
            onClick={() => void app.restore()}
          >
            Restore purchases
          </Button>
          <Show when={app.restoreNotice()}>
            {(t) => (
              <p role="status" class="mt-2 font-semibold">
                {t()}
              </p>
            )}
          </Show>
        </Card>

        <Card>
          <h2 class="text-lg font-black uppercase">Privacy</h2>
          <div class="mt-2 space-y-2 text-cream/90">
            <p>
              Gameplay works fully offline. Player names, words, roles and votes stay on this phone
              and are never sent anywhere by Bluff Club.
            </p>
            <p>
              Purchases go through Google Play and RevenueCat, which process purchase records and an
              anonymous app user ID to confirm what you own.
            </p>
            <p>
              During a round, Android screenshots and the recent-apps preview are blocked where the
              phone supports it. That can’t stop someone photographing the screen.
            </p>
            <p>
              Screen readers can read cards aloud. If you use one, use headphones or lower the
              volume during reveals.
            </p>
          </div>
          <Show
            when={appConfig.privacyPolicyUrl}
            fallback={
              <p class="mt-3 text-muted">
                The full privacy policy link is not configured in this build.
              </p>
            }
          >
            {(url) => (
              <a
                class="mt-3 inline-flex min-h-12 items-center font-bold text-lime underline underline-offset-4"
                href={url()}
                target="_blank"
                rel="noopener noreferrer"
              >
                Read the privacy policy
              </a>
            )}
          </Show>
        </Card>

        <Card>
          <h2 class="text-lg font-black uppercase">Help & support</h2>
          <Show
            when={appConfig.supportEmail}
            fallback={
              <p class="mt-1 text-muted">A support contact is not configured in this build.</p>
            }
          >
            {(email) => (
              <a
                class="mt-1 inline-flex min-h-12 items-center font-bold text-lime underline underline-offset-4"
                href={`mailto:${email()}?subject=${encodeURIComponent(`${brand.displayName} ${appConfig.version} feedback`)}`}
              >
                Email {email()}
              </a>
            )}
          </Show>
        </Card>

        <Card>
          <h2 class="text-lg font-black uppercase">Playtest stats</h2>
          <p class="mt-1 text-muted">
            Simple counters kept only on this phone (rounds played, purchases tried). No names,
            words, roles or votes. Nothing is sent unless you choose to share it.
          </p>
          <Show when={counters()}>
            {(c) => (
              <p class="mt-2 font-semibold">
                {c().round_complete} rounds finished · {c().session_start} sessions
              </p>
            )}
          </Show>
          <Button variant="secondary" block class="mt-3" onClick={() => void exportStats()}>
            Copy stats to share with the developers
          </Button>
          <Show when={exported()}>
            {(text) => (
              <pre
                class="mt-3 max-h-48 overflow-auto rounded-xl bg-ink p-3 text-xs whitespace-pre-wrap"
                role="status"
              >
                {text()}
              </pre>
            )}
          </Show>
        </Card>

        <Card>
          <h2 class="text-lg font-black uppercase">Reset</h2>
          <p class="mt-1 text-muted">Resetting never cancels or refunds a purchase.</p>
          <div class="mt-3 space-y-3">
            <Button variant="secondary" block onClick={() => confirmReset('game')}>
              Clear scores & saved round
            </Button>
            <Button variant="danger" block onClick={() => confirmReset('all')}>
              Reset all app data
            </Button>
          </div>
          <Show when={resetDone()}>
            {(t) => (
              <Notice tone="success" class="mt-3" live>
                {t()}
              </Notice>
            )}
          </Show>
        </Card>

        <p class="pb-4 text-center text-muted">
          {brand.displayName} {appConfig.version}
          <Show when={appConfig.buildMode !== 'production'}> · {appConfig.buildMode} build</Show>
          <Show when={app.isDemoStore}> · demo store</Show>
        </p>
      </div>
    </Screen>
  );
}
