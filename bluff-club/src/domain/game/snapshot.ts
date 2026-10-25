import { MAX_PLAYERS, MIN_PLAYERS, type Player, type PlayerId } from './roster';
import type { TimerState } from './timer';
import {
  type Ballot,
  type Phase,
  type ResumablePhase,
  type Round,
  type RoundResult,
  type Session,
  type Tally,
  TIMER_CHOICES,
} from './types';

/**
 * Runtime validation for persisted sessions. Anything that does not satisfy the
 * game's invariants is rejected as a whole, so a corrupted snapshot can never
 * produce an impossible round (e.g. two bluffers or a vote for oneself).
 */
export class SnapshotError extends Error {}

type Obj = Record<string, unknown>;

const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const isStr = (v: unknown): v is string => typeof v === 'string' && v.length > 0;
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

function check(cond: unknown, message: string): asserts cond {
  if (!cond) throw new SnapshotError(message);
}

function parsePlayers(v: unknown): Player[] {
  check(Array.isArray(v), 'players must be an array');
  check(v.length >= MIN_PLAYERS && v.length <= MAX_PLAYERS, 'player count out of range');
  const players = v.map((p) => {
    check(isObj(p) && isStr(p.id) && isStr(p.name), 'invalid player');
    return { id: p.id, name: p.name };
  });
  check(new Set(players.map((p) => p.id)).size === players.length, 'duplicate player IDs');
  return players;
}

function parseTimer(v: unknown): TimerState {
  check(isObj(v), 'invalid timer');
  const { durationMs, accumulatedMs, runningSince, started } = v;
  check(durationMs === null || (isNum(durationMs) && durationMs > 0), 'invalid timer duration');
  check(isNum(accumulatedMs) && accumulatedMs >= 0, 'invalid timer elapsed');
  check(runningSince === null || isNum(runningSince), 'invalid timer start');
  check(typeof started === 'boolean', 'invalid timer flag');
  return { durationMs, accumulatedMs, runningSince, started };
}

function parseTally(v: unknown, ids: ReadonlySet<PlayerId>): Tally {
  check(isObj(v), 'invalid tally');
  const tally: Record<PlayerId, number> = {};
  for (const [id, count] of Object.entries(v)) {
    check(ids.has(id) && isInt(count) && count >= 0, 'invalid tally entry');
    tally[id] = count;
  }
  return tally;
}

function parseBallot(v: unknown, players: readonly Player[]): Ballot {
  check(isObj(v), 'invalid ballot');
  const ids = new Set(players.map((p) => p.id));
  check(v.kind === 'initial' || v.kind === 'runoff', 'invalid ballot kind');
  check(Array.isArray(v.candidates) && v.candidates.length >= 2, 'invalid candidates');
  const candidates = v.candidates as unknown[];
  check(
    candidates.every((c) => typeof c === 'string' && ids.has(c)),
    'unknown candidate',
  );
  check(new Set(candidates).size === candidates.length, 'duplicate candidates');
  check(isObj(v.votes), 'invalid votes');
  const votes: Record<PlayerId, PlayerId> = {};
  for (const [voter, target] of Object.entries(v.votes)) {
    check(ids.has(voter), 'unknown voter');
    check(typeof target === 'string' && candidates.includes(target), 'vote for non-candidate');
    check(voter !== target, 'self vote');
    votes[voter] = target;
  }
  return { kind: v.kind, candidates: candidates as PlayerId[], votes };
}

/** Votes must have been cast in roster order, exactly by voters before `voterIndex`. */
function checkVoteProgress(ballot: Ballot, players: readonly Player[], voterIndex: number) {
  check(isInt(voterIndex) && voterIndex >= 0 && voterIndex < players.length, 'bad voter index');
  const expected = players.slice(0, voterIndex).map((p) => p.id);
  const actual = Object.keys(ballot.votes);
  check(
    actual.length === expected.length && expected.every((id) => actual.includes(id)),
    'vote progress mismatch',
  );
}

function parseResult(v: unknown, players: readonly Player[]): RoundResult {
  check(isObj(v), 'invalid result');
  const ids = new Set(players.map((p) => p.id));
  check(v.outcome === 'informed-win' || v.outcome === 'bluffer-win', 'invalid outcome');
  check(
    v.reason === 'caught' || v.reason === 'wrong-accusation' || v.reason === 'runoff-tie',
    'invalid reason',
  );
  check(
    v.accusedId === null || (typeof v.accusedId === 'string' && ids.has(v.accusedId)),
    'accused',
  );
  check(typeof v.blufferId === 'string' && ids.has(v.blufferId), 'invalid result bluffer');
  check(isStr(v.word), 'invalid result word');
  const points = parseTally(v.pointsAwarded, ids);
  return {
    outcome: v.outcome,
    reason: v.reason,
    accusedId: v.accusedId,
    blufferId: v.blufferId,
    word: v.word,
    initialTally: parseTally(v.initialTally, ids),
    runoffTally: v.runoffTally === null ? null : parseTally(v.runoffTally, ids),
    pointsAwarded: points,
  };
}

function parsePhase(v: unknown, players: readonly Player[], depth = 0): Phase {
  check(isObj(v), 'invalid phase');
  const n = players.length;
  switch (v.kind) {
    case 'handover':
    case 'reveal':
      check(isInt(v.index) && v.index >= 0 && v.index < n, 'bad reveal index');
      return { kind: v.kind, index: v.index };
    case 'clues':
      return { kind: 'clues' };
    case 'discussion':
      return { kind: 'discussion', timer: parseTimer(v.timer) };
    case 'vote-handover':
    case 'vote': {
      const ballot = parseBallot(v.ballot, players);
      checkVoteProgress(ballot, players, v.voterIndex as number);
      return { kind: v.kind, ballot, voterIndex: v.voterIndex as number };
    }
    case 'runoff-intro': {
      const ids = new Set(players.map((p) => p.id));
      check(Array.isArray(v.candidates) && v.candidates.length >= 2, 'bad runoff candidates');
      const candidates = v.candidates as unknown[];
      check(
        candidates.every((c) => typeof c === 'string' && ids.has(c)),
        'unknown runoff candidate',
      );
      return {
        kind: 'runoff-intro',
        candidates: candidates as PlayerId[],
        initialTally: parseTally(v.initialTally, ids),
        timer: parseTimer(v.timer),
      };
    }
    case 'paused': {
      check(depth === 0, 'nested pause');
      check(v.reason === 'interrupted' || v.reason === 'recovered', 'bad pause reason');
      const resume = parsePhase(v.resume, players, depth + 1);
      check(
        resume.kind !== 'reveal' &&
          resume.kind !== 'vote' &&
          resume.kind !== 'paused' &&
          resume.kind !== 'results',
        'pause must resume into a public phase',
      );
      return { kind: 'paused', reason: v.reason, resume: resume as ResumablePhase };
    }
    case 'results':
      return { kind: 'results', result: parseResult(v.result, players) };
    default:
      throw new SnapshotError('unknown phase');
  }
}

function parseRound(v: unknown, players: readonly Player[]): Round {
  check(isObj(v), 'invalid round');
  const ids = new Set(players.map((p) => p.id));
  check(isStr(v.id) && isInt(v.number) && v.number >= 1, 'invalid round id');
  check(isStr(v.packId) && isStr(v.wordId) && isStr(v.word), 'invalid round content');
  check(typeof v.blufferId === 'string' && ids.has(v.blufferId), 'invalid bluffer');
  check(typeof v.startingPlayerId === 'string' && ids.has(v.startingPlayerId), 'invalid starter');
  check(Array.isArray(v.clueOrder), 'invalid clue order');
  const clueOrder = v.clueOrder as unknown[];
  check(
    clueOrder.length === players.length &&
      new Set(clueOrder).size === players.length &&
      clueOrder.every((id) => typeof id === 'string' && ids.has(id)) &&
      clueOrder[0] === v.startingPlayerId,
    'clue order mismatch',
  );
  const phase = parsePhase(v.phase, players);
  if (phase.kind === 'results') check(phase.result.blufferId === v.blufferId, 'result mismatch');
  return {
    id: v.id,
    number: v.number,
    packId: v.packId,
    wordId: v.wordId,
    word: v.word,
    blufferId: v.blufferId,
    startingPlayerId: v.startingPlayerId,
    clueOrder: clueOrder as PlayerId[],
    initialTally: v.initialTally == null ? null : parseTally(v.initialTally, ids),
    phase,
  };
}

export function parseSession(v: unknown): Session {
  check(isObj(v), 'session must be an object');
  check(isStr(v.id) && isNum(v.createdAt), 'invalid session header');
  const players = parsePlayers(v.players);
  const ids = new Set(players.map((p) => p.id));
  check(isObj(v.config) && isStr(v.config.packId), 'invalid config');
  const timerSeconds = v.config.timerSeconds;
  check((TIMER_CHOICES as readonly unknown[]).includes(timerSeconds), 'invalid timer choice');
  const scores = parseTally(v.scores, ids);
  check(isInt(v.roundsCompleted) && v.roundsCompleted >= 0, 'invalid round count');
  check(v.lastScoredRoundId === null || isStr(v.lastScoredRoundId), 'invalid scoring guard');
  const round = v.round === null ? null : parseRound(v.round, players);
  if (round?.phase.kind === 'results') {
    check(v.lastScoredRoundId === round.id, 'completed round was not scored');
  }
  return {
    id: v.id,
    createdAt: v.createdAt,
    players,
    scores: Object.fromEntries(players.map((p) => [p.id, scores[p.id] ?? 0])),
    config: {
      packId: v.config.packId,
      timerSeconds: timerSeconds as Session['config']['timerSeconds'],
    },
    roundsCompleted: v.roundsCompleted,
    round,
    lastScoredRoundId: v.lastScoredRoundId,
  };
}

/** Returns the session or null plus a reason, never throws. */
export function safeParseSession(
  v: unknown,
): { ok: true; session: Session } | { ok: false; reason: string } {
  try {
    return { ok: true, session: parseSession(v) };
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : 'invalid snapshot' };
  }
}
