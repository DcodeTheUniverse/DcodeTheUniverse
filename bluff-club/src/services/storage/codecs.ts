import { FREE_PACK_ID, isPackId } from '../../content';
import type { DeckState } from '../../domain/game/deck';
import { MAX_PLAYERS, type Player } from '../../domain/game/roster';
import { parseSession } from '../../domain/game/snapshot';
import {
  DEFAULT_TIMER,
  type Session,
  TIMER_CHOICES,
  type TimerChoice,
} from '../../domain/game/types';
import type { Codec } from './storage';

/**
 * Every app-owned key lives under the "bc." prefix. Purchase SDK data is stored by
 * the RevenueCat SDK in its own native storage and is never touched by app resets.
 */
export const KEY_PREFIX = 'bc.';

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const bool = (v: unknown, fallback: boolean) => (typeof v === 'boolean' ? v : fallback);

export type RevealMode = 'hold' | 'tap';

export interface Settings {
  readonly sound: boolean;
  readonly haptics: boolean;
  readonly revealMode: RevealMode;
  readonly tutorialSeen: boolean;
}

export const DEFAULT_SETTINGS: Settings = {
  sound: true,
  haptics: true,
  revealMode: 'hold',
  tutorialSeen: false,
};

export const settingsCodec: Codec<Settings> = {
  key: `${KEY_PREFIX}settings`,
  version: 1,
  decode(data) {
    if (!isObj(data)) throw new Error('settings must be an object');
    return {
      sound: bool(data.sound, DEFAULT_SETTINGS.sound),
      haptics: bool(data.haptics, DEFAULT_SETTINGS.haptics),
      revealMode: data.revealMode === 'tap' ? 'tap' : 'hold',
      tutorialSeen: bool(data.tutorialSeen, false),
    };
  },
};

/** The host's last setup screen: roster + pack + timer, kept between sessions. */
export interface SetupDraft {
  readonly players: readonly Player[];
  readonly packId: string;
  readonly timerSeconds: TimerChoice;
}

export const setupCodec: Codec<SetupDraft> = {
  key: `${KEY_PREFIX}setup`,
  version: 1,
  decode(data) {
    if (!isObj(data) || !Array.isArray(data.players)) throw new Error('invalid setup');
    const players = data.players
      .filter(
        (p): p is { id: string; name: string } =>
          isObj(p) && typeof p.id === 'string' && typeof p.name === 'string',
      )
      .slice(0, MAX_PLAYERS)
      .map((p) => ({ id: p.id, name: p.name }));
    const packId =
      typeof data.packId === 'string' && isPackId(data.packId) ? data.packId : FREE_PACK_ID;
    const timerSeconds = (TIMER_CHOICES as readonly unknown[]).includes(data.timerSeconds)
      ? (data.timerSeconds as TimerChoice)
      : DEFAULT_TIMER;
    return { players, packId, timerSeconds };
  },
};

export const sessionCodec: Codec<Session> = {
  key: `${KEY_PREFIX}session`,
  version: 1,
  decode: (data) => parseSession(data),
};

export type DeckMap = Readonly<Record<string, DeckState>>;

export const decksCodec: Codec<DeckMap> = {
  key: `${KEY_PREFIX}decks`,
  version: 1,
  decode(data) {
    if (!isObj(data)) throw new Error('decks must be an object');
    const out: Record<string, DeckState> = {};
    for (const [packId, raw] of Object.entries(data)) {
      if (!isPackId(packId) || !isObj(raw)) continue;
      const strings = (v: unknown) =>
        Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
      out[packId] = {
        packId,
        contentVersion: typeof raw.contentVersion === 'number' ? raw.contentVersion : 0,
        remaining: strings(raw.remaining),
        drawn: strings(raw.drawn),
        lastWordId: typeof raw.lastWordId === 'string' ? raw.lastWordId : null,
      };
    }
    return out;
  },
};

export const ALL_APP_KEYS = [settingsCodec, setupCodec, sessionCodec, decksCodec].map((c) => c.key);
