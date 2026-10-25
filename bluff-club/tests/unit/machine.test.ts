import { describe, expect, it } from 'vitest';
import {
  abandonRound,
  applyScores,
  countVotes,
  createSession,
  currentPlayer,
  recoverSession,
  startRound,
  transition,
} from '../../src/domain/game/machine';
import { createPlayer, type Player } from '../../src/domain/game/roster';
import { safeParseSession } from '../../src/domain/game/snapshot';
import { elapsedMs, isExpired, remainingMs } from '../../src/domain/game/timer';
import type { GameAction, Session } from '../../src/domain/game/types';
import { seededRandom } from './helpers';

const NOW = 1_700_000_000_000;

function roster(n: number, seed = 1): Player[] {
  const rng = seededRandom(seed);
  return Array.from({ length: n }, (_, i) => createPlayer(rng, `P${i + 1}`));
}

function newRound(n = 4, seed = 1, timerSeconds: 60 | 90 | 120 | null = 90): Session {
  const rng = seededRandom(seed);
  const session = createSession(roster(n, seed), { packId: 'general', timerSeconds }, rng, NOW);
  return startRound(session, { wordId: 'general_001', word: 'Umbrella' }, rng);
}

function act(session: Session, action: GameAction, now = NOW): Session {
  const r = transition(session, action, now);
  if (!r.ok) throw new Error(r.error);
  return r.session;
}

function revealAll(session: Session): Session {
  let s = session;
  for (let i = 0; i < s.players.length; i++) {
    s = act(s, { type: 'OPEN_CARD' });
    s = act(s, { type: 'FINISH_REVEAL' });
  }
  return s;
}

/** Plays through voting with `choose(voterId)` giving each target. */
function vote(session: Session, choose: (voterId: string, s: Session) => string): Session {
  let s = session;
  for (let i = 0; i < s.players.length; i++) {
    s = act(s, { type: 'OPEN_BALLOT' });
    const voter = currentPlayer(s);
    if (!voter) throw new Error('no voter');
    s = act(s, { type: 'CAST_VOTE', voterId: voter.id, targetId: choose(voter.id, s) });
  }
  return s;
}

function toVoting(session: Session): Session {
  let s = revealAll(session);
  s = act(s, { type: 'START_DISCUSSION' });
  return act(s, { type: 'START_VOTING' });
}

describe('round setup', () => {
  it.each([3, 10])('assigns exactly one bluffer and a shared word with %i players', (n) => {
    for (let seed = 0; seed < 50; seed++) {
      const s = newRound(n, seed);
      const round = s.round!;
      expect(s.players.filter((p) => p.id === round.blufferId)).toHaveLength(1);
      expect(round.word).toBe('Umbrella');
      expect(round.clueOrder).toHaveLength(n);
      expect(new Set(round.clueOrder).size).toBe(n);
      expect(round.clueOrder[0]).toBe(round.startingPlayerId);
    }
  });

  it('rejects rosters outside 3–10', () => {
    const rng = seededRandom(1);
    expect(() => createSession(roster(2), { packId: 'g', timerSeconds: 90 }, rng, NOW)).toThrow();
    expect(() => createSession(roster(11), { packId: 'g', timerSeconds: 90 }, rng, NOW)).toThrow();
  });

  it('chooses the bluffer independently each round (every player can be picked)', () => {
    const rng = seededRandom(9);
    let s = createSession(roster(5), { packId: 'g', timerSeconds: 90 }, rng, NOW);
    const picked = new Set<string>();
    const starters = new Set<string>();
    for (let i = 0; i < 200; i++) {
      s = startRound(s, { wordId: 'w', word: 'Word' }, rng);
      picked.add(s.round!.blufferId);
      starters.add(s.round!.startingPlayerId);
      s = abandonRound(s);
    }
    expect(picked.size).toBe(5);
    expect(starters.size).toBe(5);
  });

  it('is deterministic for deterministic randomness', () => {
    expect(newRound(6, 33).round).toEqual(newRound(6, 33).round);
  });

  it('refuses to start a second round while one is active', () => {
    const s = newRound();
    expect(() => startRound(s, { wordId: 'x', word: 'X' }, seededRandom(1))).toThrow();
  });
});

describe('transitions', () => {
  it('rejects actions that are invalid for the current phase', () => {
    const s = newRound();
    for (const a of [
      { type: 'FINISH_REVEAL' },
      { type: 'START_DISCUSSION' },
      { type: 'START_VOTING' },
      { type: 'OPEN_BALLOT' },
      { type: 'RESUME' },
      { type: 'START_RUNOFF_VOTE' },
      { type: 'PAUSE_TIMER' },
    ] as GameAction[]) {
      expect(transition(s, a, NOW).ok).toBe(false);
    }
    expect(transition({ ...s, round: null }, { type: 'OPEN_CARD' }, NOW).ok).toBe(false);
  });

  it('walks every player through handover → reveal → confirmation in order', () => {
    let s = newRound(3);
    for (let i = 0; i < 3; i++) {
      expect(s.round!.phase).toEqual({ kind: 'handover', index: i });
      s = act(s, { type: 'OPEN_CARD' });
      expect(s.round!.phase).toEqual({ kind: 'reveal', index: i });
      // A second open (double tap) is rejected rather than skipping a player.
      expect(transition(s, { type: 'OPEN_CARD' }, NOW).ok).toBe(false);
      s = act(s, { type: 'FINISH_REVEAL' });
    }
    expect(s.round!.phase.kind).toBe('clues');
  });

  it('runs the discussion timer from timestamps and never auto-submits', () => {
    let s = revealAll(newRound(4, 1, 60));
    s = act(s, { type: 'START_DISCUSSION' }, NOW);
    const phase = s.round!.phase;
    if (phase.kind !== 'discussion') throw new Error('expected discussion');
    expect(remainingMs(phase.timer, NOW + 15_000)).toBe(45_000);
    s = act(s, { type: 'PAUSE_TIMER' }, NOW + 20_000);
    const paused = s.round!.phase;
    if (paused.kind !== 'discussion') throw new Error('expected discussion');
    expect(elapsedMs(paused.timer, NOW + 999_999)).toBe(20_000);
    s = act(s, { type: 'RESUME_TIMER' }, NOW + 100_000);
    const resumed = s.round!.phase;
    if (resumed.kind !== 'discussion') throw new Error('expected discussion');
    expect(isExpired(resumed.timer, NOW + 139_999)).toBe(false);
    expect(isExpired(resumed.timer, NOW + 140_000)).toBe(true);
    // Expiry changes nothing by itself; voting needs the explicit action.
    expect(s.round!.phase.kind).toBe('discussion');
  });

  it('supports the timer being switched off', () => {
    let s = revealAll(newRound(4, 1, null));
    s = act(s, { type: 'START_DISCUSSION' });
    const phase = s.round!.phase;
    if (phase.kind !== 'discussion') throw new Error('expected discussion');
    expect(remainingMs(phase.timer, NOW + 10 ** 9)).toBeNull();
    expect(isExpired(phase.timer, NOW + 10 ** 9)).toBe(false);
  });
});

describe('voting', () => {
  it('prevents self votes, out-of-turn votes and duplicate submissions', () => {
    let s = toVoting(newRound(4));
    s = act(s, { type: 'OPEN_BALLOT' });
    const [p1, p2] = s.players;
    expect(transition(s, { type: 'CAST_VOTE', voterId: p1!.id, targetId: p1!.id }, NOW).ok).toBe(
      false,
    );
    expect(transition(s, { type: 'CAST_VOTE', voterId: p2!.id, targetId: p1!.id }, NOW).ok).toBe(
      false,
    );
    s = act(s, { type: 'CAST_VOTE', voterId: p1!.id, targetId: p2!.id });
    // Double tap: same vote again is rejected (the phase already moved on).
    expect(transition(s, { type: 'CAST_VOTE', voterId: p1!.id, targetId: p2!.id }, NOW).ok).toBe(
      false,
    );
    expect(s.round!.phase.kind).toBe('vote-handover');
  });

  it('counts every vote and lets the informed team win when the bluffer is caught', () => {
    const start = toVoting(newRound(5, 3));
    const bluffer = start.round!.blufferId;
    const anyoneElse = start.players.find((p) => p.id !== bluffer)!.id;
    const s = vote(start, (voter) => (voter === bluffer ? anyoneElse : bluffer));
    const phase = s.round!.phase;
    if (phase.kind !== 'results') throw new Error('expected results');
    expect(phase.result.outcome).toBe('informed-win');
    expect(phase.result.reason).toBe('caught');
    expect(Object.values(phase.result.initialTally).reduce((a, b) => a + b, 0)).toBe(5);
    expect(phase.result.initialTally[bluffer]).toBe(4);
    for (const p of s.players) expect(s.scores[p.id]).toBe(p.id === bluffer ? 0 : 1);
    expect(s.roundsCompleted).toBe(1);
  });

  it('gives the bluffer two points for a wrong accusation', () => {
    const start = toVoting(newRound(4, 5));
    const bluffer = start.round!.blufferId;
    const innocents = start.players.filter((p) => p.id !== bluffer).map((p) => p.id);
    const target = innocents[0]!;
    const s = vote(start, (voter) => (voter === target ? innocents[1]! : target));
    const phase = s.round!.phase;
    if (phase.kind !== 'results') throw new Error('expected results');
    expect(phase.result.outcome).toBe('bluffer-win');
    expect(phase.result.reason).toBe('wrong-accusation');
    expect(phase.result.accusedId).toBe(target);
    for (const p of s.players) expect(s.scores[p.id]).toBe(p.id === bluffer ? 2 : 0);
  });

  it('runs exactly one runoff on a tie and resolves it', () => {
    const start = toVoting(newRound(4, 8));
    const [a, b, c, d] = start.players.map((p) => p.id) as [string, string, string, string];
    // a→b, b→a, c→a, d→b : a and b tie on 2.
    const plan: Record<string, string> = { [a]: b, [b]: a, [c]: a, [d]: b };
    let s = vote(start, (voter) => plan[voter]!);
    const intro = s.round!.phase;
    if (intro.kind !== 'runoff-intro') throw new Error('expected runoff intro');
    expect([...intro.candidates].sort()).toEqual([a, b].sort());
    expect(intro.timer.durationMs).toBe(20_000);
    expect(s.roundsCompleted).toBe(0);
    s = act(s, { type: 'START_TIMER' });
    s = act(s, { type: 'START_RUNOFF_VOTE' });
    // Runoff: candidates may not vote for themselves; only tied players are valid targets.
    s = act(s, { type: 'OPEN_BALLOT' });
    expect(transition(s, { type: 'CAST_VOTE', voterId: a, targetId: c }, NOW).ok).toBe(false);
    expect(transition(s, { type: 'CAST_VOTE', voterId: a, targetId: a }, NOW).ok).toBe(false);
    s = act(s, { type: 'CAST_VOTE', voterId: a, targetId: b });
    for (const voter of [b, c, d]) {
      s = act(s, { type: 'OPEN_BALLOT' });
      s = act(s, { type: 'CAST_VOTE', voterId: voter, targetId: voter === a ? b : a });
    }
    const results = s.round!.phase;
    if (results.kind !== 'results') throw new Error('expected results');
    expect(results.result.accusedId).toBe(a);
    expect(results.result.runoffTally).toEqual({ [a]: 3, [b]: 1 });
    expect(results.result.initialTally[a]).toBe(2);
  });

  it('gives the bluffer the win if the runoff ties again', () => {
    const start = toVoting(newRound(4, 8));
    const [a, b, c, d] = start.players.map((p) => p.id) as [string, string, string, string];
    const plan: Record<string, string> = { [a]: b, [b]: a, [c]: a, [d]: b };
    let s = vote(start, (voter) => plan[voter]!);
    s = act(s, { type: 'START_RUNOFF_VOTE' });
    s = vote(s, (voter) => plan[voter]!);
    const phase = s.round!.phase;
    if (phase.kind !== 'results') throw new Error('expected results');
    expect(phase.result.reason).toBe('runoff-tie');
    expect(phase.result.outcome).toBe('bluffer-win');
    expect(phase.result.accusedId).toBeNull();
    expect(s.scores[s.round!.blufferId]).toBe(2);
  });

  it('tallies only candidates', () => {
    expect(
      countVotes({ kind: 'runoff', candidates: ['a', 'b'], votes: { c: 'a', d: 'b', e: 'a' } }),
    ).toEqual({
      a: 2,
      b: 1,
    });
  });
});

describe('scoring, abandonment and recovery', () => {
  function finished(seed = 3): Session {
    const start = toVoting(newRound(4, seed));
    const bluffer = start.round!.blufferId;
    const other = start.players.find((p) => p.id !== bluffer)!.id;
    return vote(start, (voter) => (voter === bluffer ? other : bluffer));
  }

  it('applies scores exactly once per round', () => {
    const s = finished();
    const again = applyScores(s, s.round!.id, { [s.players[0]!.id]: 5 });
    expect(again).toBe(s);
    expect(transition(s, { type: 'CAST_VOTE', voterId: 'x', targetId: 'y' }, NOW).ok).toBe(false);
  });

  it('keeps scores across consecutive rounds', () => {
    let s = finished();
    const after1 = { ...s.scores };
    s = abandonRound(s);
    s = startRound(s, { wordId: 'w2', word: 'Kettle' }, seededRandom(77));
    expect(s.round!.number).toBe(2);
    expect(s.scores).toEqual(after1);
  });

  it('awards no points for abandoned rounds', () => {
    const s = abandonRound(toVoting(newRound()));
    expect(Object.values(s.scores).every((v) => v === 0)).toBe(true);
    expect(s.round).toBeNull();
    expect(s.roundsCompleted).toBe(0);
  });

  it('recovers private screens into neutral, paused public phases', () => {
    let s = newRound(4);
    s = act(s, { type: 'OPEN_CARD' });
    s = act(s, { type: 'FINISH_REVEAL' });
    s = act(s, { type: 'OPEN_CARD' }); // player 2 is looking at their card
    const recovered = recoverSession(s, NOW);
    expect(recovered.round!.phase).toEqual({
      kind: 'paused',
      reason: 'recovered',
      resume: { kind: 'handover', index: 1 },
    });
    const resumed = act(recovered, { type: 'RESUME' });
    expect(resumed.round!.phase).toEqual({ kind: 'handover', index: 1 });
  });

  it('keeps submitted votes and drops an unsubmitted selection on recovery', () => {
    let s = toVoting(newRound(4, 2));
    s = act(s, { type: 'OPEN_BALLOT' });
    const v1 = currentPlayer(s)!;
    const t = s.players.find((p) => p.id !== v1.id)!;
    s = act(s, { type: 'CAST_VOTE', voterId: v1.id, targetId: t.id });
    s = act(s, { type: 'OPEN_BALLOT' }); // voter 2 is choosing
    const r = recoverSession(s, NOW);
    const phase = r.round!.phase;
    if (phase.kind !== 'paused' || phase.resume.kind !== 'vote-handover') throw new Error('bad');
    expect(phase.resume.voterIndex).toBe(1);
    expect(phase.resume.ballot.votes).toEqual({ [v1.id]: t.id });
  });

  it('pauses a running timer on recovery and requires explicit resume', () => {
    let s = revealAll(newRound(4, 1, 60));
    s = act(s, { type: 'START_DISCUSSION' }, NOW);
    const r = recoverSession(s, NOW + 10_000);
    const phase = r.round!.phase;
    if (phase.kind !== 'paused' || phase.resume.kind !== 'discussion') throw new Error('bad');
    expect(phase.resume.timer.runningSince).toBeNull();
    expect(phase.resume.timer.accumulatedMs).toBe(10_000);
    expect(transition(r, { type: 'START_VOTING' }, NOW).ok).toBe(false);
  });

  it('interrupts and resumes from the same public phase', () => {
    let s = revealAll(newRound(4));
    s = act(s, { type: 'INTERRUPT', reason: 'interrupted' });
    expect(s.round!.phase.kind).toBe('paused');
    expect(transition(s, { type: 'INTERRUPT', reason: 'interrupted' }, NOW).ok).toBe(true);
    s = act(s, { type: 'RESUME' });
    expect(s.round!.phase.kind).toBe('clues');
  });

  it('leaves completed rounds untouched on recovery', () => {
    const s = finished();
    expect(recoverSession(s, NOW)).toBe(s);
  });
});

describe('snapshots', () => {
  it('round-trips valid sessions through JSON', () => {
    let s = toVoting(newRound(5, 4));
    s = act(s, { type: 'OPEN_BALLOT' });
    const parsed = safeParseSession(JSON.parse(JSON.stringify(s)));
    expect(parsed.ok && parsed.session).toEqual(s);
  });

  it('rejects corrupted or impossible snapshots', () => {
    const s = toVoting(newRound(4, 4));
    const json = () => JSON.parse(JSON.stringify(s));
    const cases: unknown[] = [
      null,
      'garbage',
      { ...json(), players: [] },
      { ...json(), round: { ...json().round, blufferId: 'nobody' } },
      { ...json(), config: { packId: 'general', timerSeconds: 45 } },
      {
        ...json(),
        round: {
          ...json().round,
          phase: {
            kind: 'vote-handover',
            voterIndex: 1,
            ballot: {
              kind: 'initial',
              candidates: json().players.map((p: Player) => p.id),
              votes: {},
            },
          },
        },
      },
      {
        ...json(),
        round: {
          ...json().round,
          phase: { kind: 'paused', reason: 'recovered', resume: { kind: 'reveal', index: 0 } },
        },
      },
    ];
    for (const c of cases) expect(safeParseSession(c).ok).toBe(false);
  });

  it('rejects a self vote smuggled into a snapshot', () => {
    const s = toVoting(newRound(4, 4));
    const json = JSON.parse(JSON.stringify(s));
    const p0 = json.players[0].id;
    json.round.phase = {
      kind: 'vote-handover',
      voterIndex: 1,
      ballot: {
        kind: 'initial',
        candidates: json.players.map((p: Player) => p.id),
        votes: { [p0]: p0 },
      },
    };
    expect(safeParseSession(json).ok).toBe(false);
  });
});
