import { describe, expect, it } from 'vitest';
import type { Session } from '../../src/domain/game/types';
import { createMockBilling } from '../../src/services/billing/mock';
import type { BillingAdapter } from '../../src/services/billing/types';
import { createUnavailableBilling } from '../../src/services/billing/unavailable';
import { createMemoryStore } from '../../src/services/storage/storage';
import { makeApp } from './harness';

const tick = () => new Promise((r) => setTimeout(r, 0));

function sessionOf(store: ReturnType<typeof createMemoryStore>): Session | null {
  const raw = store.data.get('bc.session');
  return raw ? (JSON.parse(raw).data as Session) : null;
}

async function startGame(h: Awaited<ReturnType<typeof makeApp>>) {
  h.app.navigate('setup');
  await h.app.startFromSetup();
  await h.app.flush();
}

describe('app controller', () => {
  it('starts with defaults and a 4-player draft roster', async () => {
    const h = await makeApp();
    expect(h.app.ready()).toBe(true);
    expect(h.app.setup().players.map((p) => p.name)).toEqual([
      'Player 1',
      'Player 2',
      'Player 3',
      'Player 4',
    ]);
    expect(h.app.setup().packId).toBe('general');
    expect(h.app.setup().timerSeconds).toBe(90);
    expect(h.app.settings().revealMode).toBe('hold');
  });

  it('enables privacy protection before opening a card and removes it on public screens', async () => {
    const h = await makeApp();
    await startGame(h);
    expect(h.app.screen()).toBe('game');
    expect(h.fake.privacyCalls.at(-1)).toBe(true);
    const before = h.fake.privacyCalls.length;
    await h.app.openCard();
    expect(h.fake.privacyCalls.slice(before)).toContain(true);
    expect(h.app.session()?.round?.phase.kind).toBe('reveal');
    h.app.dispatch({ type: 'FINISH_REVEAL' });
    h.app.interrupt();
    h.app.goHome();
    expect(h.fake.privacyCalls.at(-1)).toBe(false);
  });

  it('pauses on backgrounding, covers secrets, and requires an explicit resume', async () => {
    const h = await makeApp();
    await startGame(h);
    await h.app.openCard();
    const coverBefore = h.app.coverSignal();
    h.fake.background();
    expect(h.app.coverSignal()).toBeGreaterThan(coverBefore);
    const phase = h.app.session()?.round?.phase;
    expect(phase?.kind).toBe('paused');
    if (phase?.kind === 'paused') expect(phase.resume).toEqual({ kind: 'handover', index: 0 });
    expect(h.app.dispatch({ type: 'OPEN_CARD' })).toBe(false);
    h.app.dispatch({ type: 'RESUME' });
    expect(h.app.session()?.round?.phase.kind).toBe('handover');
  });

  it('window blur only covers the card, it does not pause', async () => {
    const h = await makeApp();
    await startGame(h);
    await h.app.openCard();
    const c = h.app.coverSignal();
    h.fake.blur();
    expect(h.app.coverSignal()).toBe(c + 1);
    expect(h.app.session()?.round?.phase.kind).toBe('reveal');
  });

  it('handles Android back: modal first, then pause in a round, then home, then minimise', async () => {
    const h = await makeApp();
    await startGame(h);
    h.app.openPaywall('food_fight');
    h.fake.back();
    await tick();
    expect(h.app.modal()).toBeNull();
    expect(h.app.session()?.round?.phase.kind).toBe('handover');
    h.fake.back();
    await tick();
    expect(h.app.session()?.round?.phase.kind).toBe('paused');
    expect(h.app.screen()).toBe('game');
    h.fake.back();
    await tick();
    expect(h.app.screen()).toBe('home');
    // The round is kept, never silently discarded.
    expect(h.app.session()?.round?.phase.kind).toBe('paused');
    h.fake.back();
    await tick();
    expect(h.fake.minimized()).toBe(1);
  });

  it('back from secondary screens returns to the previous screen', async () => {
    const h = await makeApp();
    h.app.navigate('settings');
    h.app.navigate('tutorial');
    h.fake.back();
    await tick();
    expect(h.app.screen()).toBe('settings');
    h.fake.back();
    await tick();
    expect(h.app.screen()).toBe('home');
  });

  it('persists result and scores together, exactly once, and recovers after restart', async () => {
    const h = await makeApp({ seed: 5 });
    await startGame(h);
    const app = h.app;
    for (let i = 0; i < 4; i++) {
      await app.openCard();
      app.dispatch({ type: 'FINISH_REVEAL' });
    }
    app.dispatch({ type: 'START_DISCUSSION' });
    app.dispatch({ type: 'START_VOTING' });
    const s = app.session() as Session;
    const bluffer = s.round?.blufferId as string;
    for (const voter of s.players) {
      app.dispatch({ type: 'OPEN_BALLOT' });
      const target =
        voter.id === bluffer ? (s.players.find((p) => p.id !== bluffer)?.id as string) : bluffer;
      app.dispatch({ type: 'CAST_VOTE', voterId: voter.id, targetId: target });
      // A duplicate submission (double tap) is ignored.
      expect(app.dispatch({ type: 'CAST_VOTE', voterId: voter.id, targetId: target })).toBe(false);
    }
    await app.flush();
    const saved = sessionOf(h.store);
    expect(saved?.round?.phase.kind).toBe('results');
    expect(saved?.roundsCompleted).toBe(1);
    expect(Object.values(saved?.scores ?? {}).reduce((a, b) => a + b, 0)).toBe(3);
    expect(saved?.lastScoredRoundId).toBe(saved?.round?.id);

    // "Restart" the app on the same storage: scores are not applied twice.
    h.dispose();
    const again = await makeApp({ store: h.store, seed: 6 });
    expect(again.app.session()?.roundsCompleted).toBe(1);
    expect(Object.values(again.app.session()?.scores ?? {}).reduce((a, b) => a + b, 0)).toBe(3);
    await again.app.playAgain();
    expect(again.app.session()?.round?.number).toBe(2);
    expect(Object.values(again.app.session()?.scores ?? {}).reduce((a, b) => a + b, 0)).toBe(3);
  });

  it('restores an active round into a paused recovery screen', async () => {
    const h = await makeApp();
    await startGame(h);
    await h.app.openCard();
    await h.app.flush();
    h.dispose();
    const again = await makeApp({ store: h.store });
    expect(again.app.screen()).toBe('game');
    const phase = again.app.session()?.round?.phase;
    expect(phase).toEqual({
      kind: 'paused',
      reason: 'recovered',
      resume: { kind: 'handover', index: 0 },
    });
  });

  it('discards a corrupt session with a notice but keeps settings and the demo store records', async () => {
    const store = createMemoryStore({
      'bc.session': JSON.stringify({ v: 1, savedAt: 1, data: { players: 'broken' } }),
      'bc.settings': JSON.stringify({ v: 1, savedAt: 1, data: { sound: false } }),
      'demo-store.purchases': JSON.stringify(['bluffclub_wild_card']),
    });
    const h = await makeApp({ store });
    await h.app.flush();
    expect(h.app.session()).toBeNull();
    expect(h.app.notices()[0]?.text).toMatch(/could not be restored/);
    expect(h.app.settings().sound).toBe(false);
    expect(h.store.data.has('bc.session')).toBe(false);
    expect(h.app.owned().has('pack_wild_card')).toBe(true);
  });

  it('asks before starting a new score session when the roster changes', async () => {
    const h = await makeApp({ seed: 3 });
    await startGame(h);
    // Finish quickly by abandoning; then fake a completed round count.
    const s = h.app.session() as Session;
    h.app.dispatch({ type: 'INTERRUPT', reason: 'interrupted' });
    h.app.confirmAbandon();
    const modal = h.app.modal();
    if (modal?.kind !== 'confirm') throw new Error('expected confirm');
    await modal.onConfirm();
    expect(h.app.session()?.round).toBeNull();
    // Pretend a round was scored so the new-session confirmation applies.
    (h.app.session() as { roundsCompleted: number }).roundsCompleted = 1;
    h.app.addPlayer();
    expect(await h.app.startFromSetup()).toBe(false);
    expect(h.app.modal()?.kind).toBe('confirm');
    expect(s.players).toHaveLength(4);
  });

  it('refuses to start a round with a locked pack', async () => {
    const h = await makeApp();
    h.app.updateSetup({ packId: 'wild_card' });
    expect(await h.app.startFromSetup()).toBe(false);
    expect(h.app.session()).toBeNull();
  });

  it('keeps confirmed ownership when a later refresh fails (no revocation on network failure)', async () => {
    let failRefresh = false;
    const store = createMemoryStore({
      'demo-store.purchases': JSON.stringify(['bluffclub_food_fight']),
    });
    const mock = createMockBilling(store, { delayMs: 0 });
    const billing: BillingAdapter = {
      ...mock,
      refreshOwnership: async () =>
        failRefresh ? { status: 'offline', message: 'offline' } : mock.refreshOwnership(),
    };
    const h = await makeApp({ store, billing });
    expect(h.app.owned().has('pack_food_fight')).toBe(true);
    failRefresh = true;
    await h.app.refreshOwnership();
    expect(h.app.owned().has('pack_food_fight')).toBe(true);
    expect(h.app.ownershipStatus()).toBe('offline');
  });

  it('applies refunds/revocations reported by the store', async () => {
    const store = createMemoryStore({
      'demo-store.purchases': JSON.stringify(['bluffclub_food_fight']),
    });
    const mock = createMockBilling(store, { delayMs: 0 });
    const h = await makeApp({ store, billing: mock });
    expect(h.app.packUnlocked('food_fight')).toBe(true);
    await mock.controls.refund('bluffclub_food_fight');
    expect(h.app.packUnlocked('food_fight')).toBe(false);
  });

  it('shows purchases as unavailable without faking success', async () => {
    const h = await makeApp({ billing: createUnavailableBilling('not-native') });
    expect(h.app.ownershipStatus()).toBe('unavailable');
    await h.app.purchase('bluffclub_food_fight');
    expect(h.app.purchaseNotice()?.status).toBe('unavailable');
    expect(h.app.owned().size).toBe(0);
    await h.app.restore();
    expect(h.app.restoreNotice()).toMatch(/only available in the Bluff Club Android app/);
  });

  it('ignores a second purchase tap while one is in flight', async () => {
    const store = createMemoryStore();
    const mock = createMockBilling(store, { delayMs: 5 });
    let calls = 0;
    const billing: BillingAdapter = {
      ...mock,
      purchase: (id) => {
        calls++;
        return mock.purchase(id);
      },
    };
    const h = await makeApp({ store, billing });
    await Promise.all([
      h.app.purchase('bluffclub_food_fight'),
      h.app.purchase('bluffclub_food_fight'),
    ]);
    expect(calls).toBe(1);
    expect(h.app.owned().has('pack_food_fight')).toBe(true);
  });

  it('reset keeps purchases and only clears app keys', async () => {
    const store = createMemoryStore({
      'demo-store.purchases': JSON.stringify(['bluffclub_party_bundle']),
    });
    const h = await makeApp({ store });
    await startGame(h);
    await h.app.resetEverything();
    await h.app.flush();
    expect([...h.store.data.keys()].filter((k) => k.startsWith('bc.'))).toEqual([]);
    expect(h.store.data.has('demo-store.purchases')).toBe(true);
    expect(h.app.owned().size).toBe(4);
  });

  it('records only bounded, non-identifying metrics', async () => {
    const h = await makeApp();
    h.app.setPlayerName(h.app.setup().players[0]?.id as string, 'Secret Name');
    await startGame(h);
    await h.app.flush();
    const raw = h.store.data.get('bc.metrics') ?? '';
    expect(raw).not.toContain('Secret Name');
    const round = h.app.session()?.round;
    expect(raw).not.toContain(round?.word as string);
    expect(raw).not.toContain(round?.blufferId as string);
    const exported = h.app.metrics()?.exportJson('0.1.0') ?? '';
    expect(exported).toContain('"round_start": 1');
    expect(exported).not.toContain('Secret Name');
  });
});
