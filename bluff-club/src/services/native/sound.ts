/**
 * Optional, tiny synthesized UI sounds (no audio files, no network). Nothing in the
 * game depends on hearing these; every cue also has a visible text equivalent.
 * The same sound plays for every role, so audio never reveals who the bluffer is.
 */
export type Cue = 'tap' | 'timer-end' | 'celebrate';

let ctx: AudioContext | null = null;

function context(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const Ctor =
    window.AudioContext ??
    (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  ctx ??= new Ctor();
  return ctx;
}

const NOTES: Record<Cue, readonly [number, number][]> = {
  tap: [[660, 0.05]],
  'timer-end': [
    [523, 0.12],
    [392, 0.18],
  ],
  celebrate: [
    [523, 0.09],
    [659, 0.09],
    [784, 0.16],
  ],
};

export function playCue(cue: Cue): void {
  try {
    const audio = context();
    if (!audio) return;
    if (audio.state === 'suspended') void audio.resume();
    let at = audio.currentTime;
    for (const [freq, duration] of NOTES[cue]) {
      const osc = audio.createOscillator();
      const gain = audio.createGain();
      osc.type = 'triangle';
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, at);
      gain.gain.exponentialRampToValueAtTime(0.08, at + 0.01);
      gain.gain.exponentialRampToValueAtTime(0.0001, at + duration);
      osc.connect(gain).connect(audio.destination);
      osc.start(at);
      osc.stop(at + duration + 0.02);
      at += duration;
    }
  } catch {
    /* sound is optional */
  }
}
