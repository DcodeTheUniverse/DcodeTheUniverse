import { pickOne, type RandomSource, randomId } from '../random';
import { finalizeRoster, MAX_PLAYERS, MIN_PLAYERS, type Player, type PlayerId } from './roster';
import { createTimer, pauseTimer, startTimer, type TimerState } from './timer';
import {
  type Ballot,
  BLUFFER_WIN_POINTS,
  type GameAction,
  INFORMED_WIN_POINTS,
  type OutcomeReason,
  type Phase,
  type ResumablePhase,
  type Round,
  type RoundConfig,
  type RoundResult,
  RUNOFF_DISCUSSION_MS,
  type Session,
  type Tally,
  type TransitionResult,
} from './types';

export interface DrawnWord {
  readonly wordId: string;
  readonly word: string;
}

export function createSession(
  players: readonly Player[],
  config: RoundConfig,
  rng: RandomSource,
  now: number,
): Session {
  if (players.length < MIN_PLAYERS || players.length > MAX_PLAYERS) {
    throw new RangeError(`A session needs ${MIN_PLAYERS}–${MAX_PLAYERS} players`);
  }
  const roster = finalizeRoster(players);
  return {
    id: randomId(rng, 's'),
    createdAt: now,
    players: roster,
    scores: Object.fromEntries(roster.map((p) => [p.id, 0])),
    config,
    roundsCompleted: 0,
    round: null,
    lastScoredRoundId: null,
  };
}

/** True while a round exists and has not reached results. */
export function hasActiveRound(session: Session | null): boolean {
  return !!session?.round && session.round.phase.kind !== 'results';
}

/**
 * Starts a fresh round. The bluffer and the starting clue-giver are drawn
 * uniformly and independently every round; there is no rotation or memory.
 */
export function startRound(session: Session, drawn: DrawnWord, rng: RandomSource): Session {
  if (hasActiveRound(session)) throw new Error('A round is already in progress');
  const ids = session.players.map((p) => p.id);
  const blufferId = pickOne(rng, ids);
  const startingPlayerId = pickOne(rng, ids);
  const start = ids.indexOf(startingPlayerId);
  const round: Round = {
    id: randomId(rng, 'r'),
    number: session.roundsCompleted + 1,
    packId: session.config.packId,
    wordId: drawn.wordId,
    word: drawn.word,
    blufferId,
    startingPlayerId,
    clueOrder: [...ids.slice(start), ...ids.slice(0, start)],
    initialTally: null,
    phase: { kind: 'handover', index: 0 },
  };
  return { ...session, round };
}

/** Abandoning never awards points; the score session itself is kept. */
export function abandonRound(session: Session): Session {
  return { ...session, round: null };
}

export function updateConfig(session: Session, config: RoundConfig): Session {
  if (hasActiveRound(session)) throw new Error('Cannot change settings during a round');
  return { ...session, config };
}

const fail = (error: string): TransitionResult => ({ ok: false, error });

function withPhase(session: Session, round: Round, phase: Phase, patch?: Partial<Round>) {
  return { ok: true as const, session: { ...session, round: { ...round, ...patch, phase } } };
}

/** Maps any live phase to the public phase it is safe to resume into. */
export function toResumable(phase: Phase, now: number): ResumablePhase | null {
  switch (phase.kind) {
    case 'handover':
    case 'clues':
    case 'vote-handover':
      return phase;
    case 'reveal':
      return { kind: 'handover', index: phase.index };
    case 'vote':
      // Any unsubmitted selection lives only in the UI and is discarded.
      return { kind: 'vote-handover', ballot: phase.ballot, voterIndex: phase.voterIndex };
    case 'discussion':
      return { kind: 'discussion', timer: pauseTimer(phase.timer, now) };
    case 'runoff-intro':
      return { ...phase, timer: pauseTimer(phase.timer, now) };
    case 'paused':
      return phase.resume;
    case 'results':
      return null;
  }
}

export function transition(session: Session, action: GameAction, now: number): TransitionResult {
  const round = session.round;
  if (!round) return fail('No round in progress');
  const phase = round.phase;
  const players = session.players;

  switch (action.type) {
    case 'OPEN_CARD':
      if (phase.kind !== 'handover') return fail(`Cannot open a card during ${phase.kind}`);
      return withPhase(session, round, { kind: 'reveal', index: phase.index });

    case 'FINISH_REVEAL': {
      if (phase.kind !== 'reveal') return fail(`Cannot finish a reveal during ${phase.kind}`);
      const next = phase.index + 1;
      if (next < players.length)
        return withPhase(session, round, { kind: 'handover', index: next });
      return withPhase(session, round, { kind: 'clues' });
    }

    case 'START_DISCUSSION': {
      if (phase.kind !== 'clues') return fail(`Cannot start discussion during ${phase.kind}`);
      const seconds = session.config.timerSeconds;
      const timer = startTimer(createTimer(seconds === null ? null : seconds * 1000), now);
      return withPhase(session, round, { kind: 'discussion', timer });
    }

    case 'START_TIMER':
    case 'RESUME_TIMER':
    case 'PAUSE_TIMER': {
      if (phase.kind !== 'discussion' && phase.kind !== 'runoff-intro') {
        return fail(`No timer during ${phase.kind}`);
      }
      const update = (t: TimerState) =>
        action.type === 'PAUSE_TIMER' ? pauseTimer(t, now) : startTimer(t, now);
      if (action.type === 'START_TIMER' && phase.timer.started)
        return fail('Timer already started');
      if (action.type === 'RESUME_TIMER' && !phase.timer.started) return fail('Timer not started');
      return withPhase(session, round, { ...phase, timer: update(phase.timer) });
    }

    case 'START_VOTING': {
      if (phase.kind !== 'discussion') return fail(`Cannot start voting during ${phase.kind}`);
      const ballot: Ballot = { kind: 'initial', candidates: players.map((p) => p.id), votes: {} };
      return withPhase(session, round, { kind: 'vote-handover', ballot, voterIndex: 0 });
    }

    case 'OPEN_BALLOT':
      if (phase.kind !== 'vote-handover') return fail(`Cannot open a ballot during ${phase.kind}`);
      return withPhase(session, round, {
        kind: 'vote',
        ballot: phase.ballot,
        voterIndex: phase.voterIndex,
      });

    case 'CAST_VOTE': {
      if (phase.kind !== 'vote') return fail(`Cannot vote during ${phase.kind}`);
      const voter = players[phase.voterIndex];
      if (!voter || voter.id !== action.voterId)
        return fail('It is not this player’s turn to vote');
      if (Object.hasOwn(phase.ballot.votes, voter.id)) return fail('This player already voted');
      if (action.targetId === voter.id) return fail('Players cannot vote for themselves');
      if (!phase.ballot.candidates.includes(action.targetId)) return fail('Not a valid candidate');
      const ballot: Ballot = {
        ...phase.ballot,
        votes: { ...phase.ballot.votes, [voter.id]: action.targetId },
      };
      const nextIndex = phase.voterIndex + 1;
      if (nextIndex < players.length) {
        return withPhase(session, round, { kind: 'vote-handover', ballot, voterIndex: nextIndex });
      }
      return closeBallot(session, round, ballot);
    }

    case 'START_RUNOFF_VOTE': {
      if (phase.kind !== 'runoff-intro') return fail(`Cannot start a runoff during ${phase.kind}`);
      const ballot: Ballot = { kind: 'runoff', candidates: phase.candidates, votes: {} };
      return withPhase(session, round, { kind: 'vote-handover', ballot, voterIndex: 0 });
    }

    case 'INTERRUPT': {
      if (phase.kind === 'results') return fail('Nothing to pause on the results screen');
      const resume = toResumable(phase, now);
      if (!resume) return fail('Nothing to pause');
      return withPhase(session, round, { kind: 'paused', reason: action.reason, resume });
    }

    case 'RESUME':
      if (phase.kind !== 'paused') return fail('The round is not paused');
      return withPhase(session, round, phase.resume);
  }
}

export function countVotes(ballot: Ballot): Tally {
  const tally: Record<PlayerId, number> = Object.fromEntries(ballot.candidates.map((c) => [c, 0]));
  for (const target of Object.values(ballot.votes)) {
    if (Object.hasOwn(tally, target)) tally[target] = (tally[target] ?? 0) + 1;
  }
  return tally;
}

export function leaders(tally: Tally): PlayerId[] {
  const max = Math.max(0, ...Object.values(tally));
  return Object.entries(tally)
    .filter(([, count]) => count === max)
    .map(([id]) => id);
}

function closeBallot(session: Session, round: Round, ballot: Ballot): TransitionResult {
  if (Object.keys(ballot.votes).length !== session.players.length) {
    return fail('Not everyone has voted yet');
  }
  const tally = countVotes(ballot);
  const top = leaders(tally);

  if (ballot.kind === 'initial') {
    if (top.length > 1) {
      return withPhase(
        session,
        round,
        {
          kind: 'runoff-intro',
          candidates: top,
          initialTally: tally,
          timer: createTimer(RUNOFF_DISCUSSION_MS),
        },
        { initialTally: tally },
      );
    }
    return finishRound(session, round, top[0] ?? null, tally, null);
  }
  const initialTally = round.initialTally ?? {};
  return finishRound(
    session,
    round,
    top.length === 1 ? (top[0] ?? null) : null,
    initialTally,
    tally,
  );
}

/** Resolves the winner and applies scores in the same immutable update. */
function finishRound(
  session: Session,
  round: Round,
  accusedId: PlayerId | null,
  initialTally: Tally,
  runoffTally: Tally | null,
): TransitionResult {
  let reason: OutcomeReason;
  if (accusedId === null) reason = 'runoff-tie';
  else if (accusedId === round.blufferId) reason = 'caught';
  else reason = 'wrong-accusation';
  const outcome = reason === 'caught' ? 'informed-win' : 'bluffer-win';

  const pointsAwarded: Record<PlayerId, number> = {};
  for (const p of session.players) {
    if (outcome === 'informed-win')
      pointsAwarded[p.id] = p.id === round.blufferId ? 0 : INFORMED_WIN_POINTS;
    else pointsAwarded[p.id] = p.id === round.blufferId ? BLUFFER_WIN_POINTS : 0;
  }
  const result: RoundResult = {
    outcome,
    reason,
    accusedId,
    blufferId: round.blufferId,
    word: round.word,
    initialTally,
    runoffTally,
    pointsAwarded,
  };
  const scored = applyScores(session, round.id, pointsAwarded);
  return {
    ok: true,
    session: { ...scored, round: { ...round, phase: { kind: 'results', result } } },
  };
}

/** Idempotent: a round ID that was already scored never scores again. */
export function applyScores(
  session: Session,
  roundId: string,
  points: Readonly<Record<PlayerId, number>>,
): Session {
  if (session.lastScoredRoundId === roundId) return session;
  const scores: Record<PlayerId, number> = { ...session.scores };
  for (const p of session.players) scores[p.id] = (scores[p.id] ?? 0) + (points[p.id] ?? 0);
  return {
    ...session,
    scores,
    roundsCompleted: session.roundsCompleted + 1,
    lastScoredRoundId: roundId,
  };
}

/** After process death: never resume straight into a private screen or a running timer. */
export function recoverSession(session: Session, now: number): Session {
  const round = session.round;
  if (!round || round.phase.kind === 'results') return session;
  const resume = toResumable(round.phase, now);
  if (!resume) return session;
  return {
    ...session,
    round: { ...round, phase: { kind: 'paused', reason: 'recovered', resume } },
  };
}

export function currentPlayer(session: Session): Player | null {
  const phase = session.round?.phase;
  if (!phase) return null;
  if (phase.kind === 'handover' || phase.kind === 'reveal')
    return session.players[phase.index] ?? null;
  if (phase.kind === 'vote-handover' || phase.kind === 'vote') {
    return session.players[phase.voterIndex] ?? null;
  }
  return null;
}

export function standings(session: Session): { player: Player; score: number }[] {
  return session.players
    .map((player) => ({ player, score: session.scores[player.id] ?? 0 }))
    .sort((a, b) => b.score - a.score);
}
