import { isPackId } from '../../content';
import { KEY_PREFIX } from '../storage/codecs';
import type { Codec, Repository } from '../storage/storage';

/**
 * Minimal, local-only measurement. Events carry no player names, words, roles,
 * ballots or free text — only fixed event names and known pack/product IDs.
 * Counters are bounded (a fixed key set) and never leave the device unless a
 * playtester explicitly exports them. There is no third-party analytics SDK.
 */
export type MetricEvent =
  | { readonly name: 'session_start'; readonly players: number }
  | { readonly name: 'round_start'; readonly packId: string }
  | {
      readonly name: 'round_complete';
      readonly outcome: 'informed-win' | 'bluffer-win';
      readonly runoff: boolean;
    }
  | { readonly name: 'round_abandon' }
  | { readonly name: 'pack_select'; readonly packId: string }
  | { readonly name: 'paywall_view'; readonly packId: string | 'bundle' }
  | { readonly name: 'purchase_attempt' }
  | { readonly name: 'purchase_result'; readonly status: string }
  | { readonly name: 'restore_result'; readonly status: string };

export const COUNTER_KEYS = [
  'session_start',
  'round_start',
  'round_complete',
  'round_complete_informed',
  'round_complete_bluffer',
  'round_complete_runoff',
  'round_abandon',
  'paywall_view',
  'purchase_attempt',
  'purchase_success',
  'purchase_cancelled',
  'purchase_pending',
  'purchase_failed',
  'restore_restored',
  'restore_failed',
] as const;
export type CounterKey = (typeof COUNTER_KEYS)[number];

export interface MetricsSnapshot {
  readonly firstRecordedAt: number | null;
  readonly counters: Readonly<Record<CounterKey, number>>;
  /** Rounds started per pack (known pack IDs only). */
  readonly roundsByPack: Readonly<Record<string, number>>;
  /** Player-count histogram for sessions (3..10). */
  readonly sessionSizes: Readonly<Record<string, number>>;
}

export const emptyMetrics = (): MetricsSnapshot => ({
  firstRecordedAt: null,
  counters: Object.fromEntries(COUNTER_KEYS.map((k) => [k, 0])) as Record<CounterKey, number>,
  roundsByPack: {},
  sessionSizes: {},
});

const MAX_COUNT = 1_000_000;

export const metricsCodec: Codec<MetricsSnapshot> = {
  key: `${KEY_PREFIX}metrics`,
  version: 1,
  decode(data) {
    const base = emptyMetrics();
    if (typeof data !== 'object' || data === null) throw new Error('invalid metrics');
    const d = data as Partial<MetricsSnapshot>;
    const num = (v: unknown) =>
      typeof v === 'number' && Number.isInteger(v) && v >= 0 ? Math.min(v, MAX_COUNT) : 0;
    const counters = { ...base.counters };
    for (const k of COUNTER_KEYS) counters[k] = num(d.counters?.[k]);
    const roundsByPack: Record<string, number> = {};
    for (const [k, v] of Object.entries(d.roundsByPack ?? {}))
      if (isPackId(k)) roundsByPack[k] = num(v);
    const sessionSizes: Record<string, number> = {};
    for (const [k, v] of Object.entries(d.sessionSizes ?? {}))
      if (/^(?:[3-9]|10)$/.test(k)) sessionSizes[k] = num(v);
    return {
      firstRecordedAt: typeof d.firstRecordedAt === 'number' ? d.firstRecordedAt : null,
      counters,
      roundsByPack,
      sessionSizes,
    };
  },
};

export function applyEvent(m: MetricsSnapshot, event: MetricEvent, now: number): MetricsSnapshot {
  const counters = { ...m.counters };
  const bump = (k: CounterKey) => {
    counters[k] = Math.min(MAX_COUNT, counters[k] + 1);
  };
  let roundsByPack = m.roundsByPack;
  let sessionSizes = m.sessionSizes;
  switch (event.name) {
    case 'session_start': {
      bump('session_start');
      const key = String(event.players);
      if (/^(?:[3-9]|10)$/.test(key))
        sessionSizes = { ...sessionSizes, [key]: (sessionSizes[key] ?? 0) + 1 };
      break;
    }
    case 'round_start':
      bump('round_start');
      if (isPackId(event.packId)) {
        roundsByPack = { ...roundsByPack, [event.packId]: (roundsByPack[event.packId] ?? 0) + 1 };
      }
      break;
    case 'round_complete':
      bump('round_complete');
      bump(event.outcome === 'informed-win' ? 'round_complete_informed' : 'round_complete_bluffer');
      if (event.runoff) bump('round_complete_runoff');
      break;
    case 'round_abandon':
      bump('round_abandon');
      break;
    case 'pack_select':
      // Selection is reflected in roundsByPack when a round starts; nothing else stored.
      break;
    case 'paywall_view':
      bump('paywall_view');
      break;
    case 'purchase_attempt':
      bump('purchase_attempt');
      break;
    case 'purchase_result':
      if (event.status === 'success') bump('purchase_success');
      else if (event.status === 'cancelled') bump('purchase_cancelled');
      else if (event.status === 'pending') bump('purchase_pending');
      else bump('purchase_failed');
      break;
    case 'restore_result':
      bump(event.status === 'restored' ? 'restore_restored' : 'restore_failed');
      break;
  }
  return { firstRecordedAt: m.firstRecordedAt ?? now, counters, roundsByPack, sessionSizes };
}

export interface Metrics {
  track(event: MetricEvent): void;
  snapshot(): MetricsSnapshot;
  reset(): Promise<void>;
  /** Explicit, consented export for playtesters: aggregate counters only. */
  exportJson(appVersion: string): string;
}

export async function createMetrics(
  repo: Repository,
  now: () => number = Date.now,
): Promise<Metrics> {
  const loaded = await repo.load(metricsCodec);
  let state = loaded.status === 'ok' ? loaded.value : emptyMetrics();
  return {
    track(event) {
      state = applyEvent(state, event, now());
      void repo.save(metricsCodec, state);
    },
    snapshot: () => state,
    async reset() {
      state = emptyMetrics();
      await repo.remove(metricsCodec);
    },
    exportJson(appVersion) {
      return JSON.stringify(
        {
          note: 'Bluff Club local playtest counters for ONE device. Not population analytics; one shared phone = one install.',
          appVersion,
          exportedAt: new Date(now()).toISOString(),
          ...state,
        },
        null,
        2,
      );
    },
  };
}
