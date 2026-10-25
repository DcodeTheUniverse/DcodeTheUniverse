import type { Player, PlayerId } from './roster';
import type { TimerState } from './timer';

export const TIMER_CHOICES = [60, 90, 120, null] as const;
export type TimerChoice = (typeof TIMER_CHOICES)[number];
export const DEFAULT_TIMER: TimerChoice = 90;
export const RUNOFF_DISCUSSION_MS = 20_000;

export const INFORMED_WIN_POINTS = 1;
export const BLUFFER_WIN_POINTS = 2;

export interface RoundConfig {
  readonly packId: string;
  readonly timerSeconds: TimerChoice;
}

export type Tally = Readonly<Record<PlayerId, number>>;

export interface Ballot {
  readonly kind: 'initial' | 'runoff';
  /** Players who may receive votes on this ballot. */
  readonly candidates: readonly PlayerId[];
  /** voterId -> targetId. Never shown publicly. */
  readonly votes: Readonly<Record<PlayerId, PlayerId>>;
}

export type Outcome = 'informed-win' | 'bluffer-win';
export type OutcomeReason = 'caught' | 'wrong-accusation' | 'runoff-tie';

export interface RoundResult {
  readonly outcome: Outcome;
  readonly reason: OutcomeReason;
  /** The single most-voted player, or null when the runoff tied again. */
  readonly accusedId: PlayerId | null;
  readonly blufferId: PlayerId;
  readonly word: string;
  readonly initialTally: Tally;
  readonly runoffTally: Tally | null;
  readonly pointsAwarded: Readonly<Record<PlayerId, number>>;
}

export type PauseReason = 'interrupted' | 'recovered';

export type HandoverPhase = { readonly kind: 'handover'; readonly index: number };
export type RevealPhase = { readonly kind: 'reveal'; readonly index: number };
export type CluesPhase = { readonly kind: 'clues' };
export type DiscussionPhase = { readonly kind: 'discussion'; readonly timer: TimerState };
export type VoteHandoverPhase = {
  readonly kind: 'vote-handover';
  readonly ballot: Ballot;
  readonly voterIndex: number;
};
export type VotePhase = {
  readonly kind: 'vote';
  readonly ballot: Ballot;
  readonly voterIndex: number;
};
export type RunoffIntroPhase = {
  readonly kind: 'runoff-intro';
  readonly candidates: readonly PlayerId[];
  readonly initialTally: Tally;
  readonly timer: TimerState;
};
/** Public phases that a pause/recovery may return to. Never a private screen. */
export type ResumablePhase =
  | HandoverPhase
  | CluesPhase
  | DiscussionPhase
  | VoteHandoverPhase
  | RunoffIntroPhase;
export type PausedPhase = {
  readonly kind: 'paused';
  readonly reason: PauseReason;
  readonly resume: ResumablePhase;
};
export type ResultsPhase = { readonly kind: 'results'; readonly result: RoundResult };

export type Phase =
  | HandoverPhase
  | RevealPhase
  | CluesPhase
  | DiscussionPhase
  | VoteHandoverPhase
  | VotePhase
  | RunoffIntroPhase
  | PausedPhase
  | ResultsPhase;

export type PhaseKind = Phase['kind'];

export interface Round {
  readonly id: string;
  readonly number: number;
  readonly packId: string;
  readonly wordId: string;
  readonly word: string;
  readonly blufferId: PlayerId;
  readonly startingPlayerId: PlayerId;
  /** Clue order: the roster rotated so the randomly chosen starter goes first. */
  readonly clueOrder: readonly PlayerId[];
  /** Initial-ballot tally kept once a runoff starts, for the results screen. */
  readonly initialTally: Tally | null;
  readonly phase: Phase;
}

export interface Session {
  readonly id: string;
  readonly createdAt: number;
  readonly players: readonly Player[];
  readonly scores: Readonly<Record<PlayerId, number>>;
  readonly config: RoundConfig;
  readonly roundsCompleted: number;
  readonly round: Round | null;
  /** Guard that makes scoring idempotent even if a completion is replayed. */
  readonly lastScoredRoundId: string | null;
}

export type GameAction =
  | { readonly type: 'OPEN_CARD' }
  | { readonly type: 'FINISH_REVEAL' }
  | { readonly type: 'START_DISCUSSION' }
  | { readonly type: 'START_TIMER' }
  | { readonly type: 'PAUSE_TIMER' }
  | { readonly type: 'RESUME_TIMER' }
  | { readonly type: 'START_VOTING' }
  | { readonly type: 'OPEN_BALLOT' }
  | { readonly type: 'CAST_VOTE'; readonly voterId: PlayerId; readonly targetId: PlayerId }
  | { readonly type: 'START_RUNOFF_VOTE' }
  | { readonly type: 'INTERRUPT'; readonly reason: PauseReason }
  | { readonly type: 'RESUME' };

export type TransitionResult =
  | { readonly ok: true; readonly session: Session }
  | { readonly ok: false; readonly error: string };
