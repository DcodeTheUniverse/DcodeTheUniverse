import { batch, createSignal } from 'solid-js';
import { FREE_PACK_ID, getPack, isPackId } from '../content';
import { drawWord } from '../domain/game/deck';
import {
  abandonRound,
  createSession,
  hasActiveRound,
  recoverSession,
  startRound,
  transition,
} from '../domain/game/machine';
import {
  createPlayer,
  defaultRoster,
  finalizeRoster,
  MAX_PLAYERS,
  MIN_PLAYERS,
  nextDefaultName,
  type Player,
  sameRosterIds,
  validateRoster,
} from '../domain/game/roster';
import type { GameAction, Session } from '../domain/game/types';
import { DEFAULT_TIMER } from '../domain/game/types';
import type { RandomSource } from '../domain/random';
import {
  type EntitlementId,
  isPackUnlocked,
  isProductOwned,
  type ProductId,
} from '../services/billing/catalog';
import type {
  BillingAdapter,
  InitResult,
  Ownership,
  ProductsResult,
  PurchaseResult,
  RestoreResult,
} from '../services/billing/types';
import { createMetrics, type Metrics } from '../services/metrics/metrics';
import type { NativeBridge } from '../services/native/native';
import { type Cue, playCue } from '../services/native/sound';
import {
  DEFAULT_SETTINGS,
  type DeckMap,
  decksCodec,
  type Settings,
  type SetupDraft,
  sessionCodec,
  settingsCodec,
  setupCodec,
} from '../services/storage/codecs';
import { type KeyValueStore, Repository } from '../services/storage/storage';

export type Screen = 'home' | 'setup' | 'game' | 'packs' | 'settings' | 'tutorial';

export interface Services {
  readonly store: KeyValueStore;
  readonly native: NativeBridge;
  readonly billing: BillingAdapter;
  readonly rng: RandomSource;
  readonly now: () => number;
  readonly sound?: (cue: Cue) => void;
}

export type Modal =
  | {
      readonly kind: 'confirm';
      readonly title: string;
      readonly body: string;
      readonly confirmLabel: string;
      readonly cancelLabel?: string;
      readonly danger?: boolean;
      readonly onConfirm: () => void | Promise<void>;
    }
  | { readonly kind: 'paywall'; readonly target: string /* pack id or 'bundle' */ };

export type OwnershipStatus = 'checking' | 'ok' | 'offline' | 'error' | 'unavailable';

export interface PurchaseNotice {
  readonly productId: ProductId;
  readonly status: PurchaseResult['status'];
  readonly text: string;
}

export interface Notice {
  readonly id: string;
  readonly tone: 'info' | 'warning' | 'error';
  readonly text: string;
}

const PURCHASE_TEXT: Record<PurchaseResult['status'], string> = {
  success: 'Unlocked! The pack is ready to play.',
  cancelled: 'Purchase cancelled. Nothing was charged.',
  pending: 'Payment pending. The pack unlocks automatically once Google Play confirms the payment.',
  'already-owned': 'You already own this. Your purchase has been restored.',
  offline: 'You’re offline. Connect to the internet to buy packs.',
  'not-available': 'This item isn’t available to buy right now.',
  unavailable: 'Purchases aren’t available in this version of the app.',
  error: 'The purchase didn’t go through. You can try again.',
};

export function createAppController(services: Services) {
  const { native, billing, rng, now } = services;
  const sound = services.sound ?? playCue;

  const [ready, setReady] = createSignal(false);
  const [screen, setScreen] = createSignal<Screen>('home');
  const [history, setHistory] = createSignal<Screen[]>([]);
  const [settings, setSettings] = createSignal<Settings>(DEFAULT_SETTINGS);
  const [setup, setSetup] = createSignal<SetupDraft>({
    players: [],
    packId: FREE_PACK_ID,
    timerSeconds: DEFAULT_TIMER,
  });
  const [session, setSession] = createSignal<Session | null>(null);
  const [decks, setDecks] = createSignal<DeckMap>({});
  const [modal, setModal] = createSignal<Modal | null>(null);
  const [notices, setNotices] = createSignal<Notice[]>([]);
  /** Incremented whenever private content must be covered immediately. */
  const [coverSignal, setCoverSignal] = createSignal(0);
  const [online, setOnline] = createSignal(native.isOnline());

  const [billingInit, setBillingInit] = createSignal<InitResult | null>(null);
  const [owned, setOwned] = createSignal<ReadonlySet<EntitlementId>>(new Set());
  const [ownershipStatus, setOwnershipStatus] = createSignal<OwnershipStatus>('checking');
  const [products, setProducts] = createSignal<ProductsResult | null>(null);
  const [productsLoading, setProductsLoading] = createSignal(false);
  const [busyProduct, setBusyProduct] = createSignal<ProductId | null>(null);
  const [purchaseNotice, setPurchaseNotice] = createSignal<PurchaseNotice | null>(null);
  const [restoring, setRestoring] = createSignal(false);
  const [restoreNotice, setRestoreNotice] = createSignal<string | null>(null);
  const [storageFailed, setStorageFailed] = createSignal(false);

  const repo = new Repository(services.store, now, () => {
    setStorageFailed(true);
  });
  let metrics: Metrics | null = null;
  const track: Metrics['track'] = (e) => metrics?.track(e);

  const dismissNotice = (id: string) => setNotices((list) => list.filter((n) => n.id !== id));
  const addNotice = (tone: Notice['tone'], text: string) => {
    const id = `n${now()}${Math.floor(Math.random() * 1000)}`;
    setNotices((list) => [...list.filter((n) => n.text !== text), { id, tone, text }]);
    if (tone === 'info') setTimeout(() => dismissNotice(id), 6000);
  };

  const feedback = (kind: 'tap' | 'success' | 'warning', cue?: Cue) => {
    if (settings().haptics) void native.haptic(kind);
    if (cue && settings().sound) sound(cue);
  };

  // ---------- persistence helpers ----------
  const syncPrivacy = () => {
    const desired = screen() === 'game' && hasActiveRound(session());
    native.setPrivacyScreen(desired).catch(() => {});
  };

  const commitSession = (next: Session | null) => {
    setSession(next);
    if (next) void repo.save(sessionCodec, next);
    else void repo.remove(sessionCodec);
    syncPrivacy();
  };

  const commitSettings = (patch: Partial<Settings>) => {
    const next = { ...settings(), ...patch };
    setSettings(next);
    void repo.save(settingsCodec, next);
  };

  const commitSetup = (next: SetupDraft) => {
    setSetup(next);
    void repo.save(setupCodec, next);
  };

  // ---------- navigation ----------
  const navigate = (to: Screen, opts: { replace?: boolean } = {}) => {
    if (to === screen()) return;
    batch(() => {
      if (!opts.replace) setHistory((h) => [...h.slice(-10), screen()]);
      setScreen(to);
    });
    syncPrivacy();
  };

  const goHome = () => {
    batch(() => {
      setHistory([]);
      setScreen('home');
    });
    syncPrivacy();
  };

  // ---------- ownership ----------
  const applyOwnership = (o: Ownership) => {
    setOwned(o.entitlements);
    setOwnershipStatus('ok');
  };

  const refreshOwnership = async () => {
    if (billingInit()?.status !== 'ready') return;
    const result = await billing.refreshOwnership();
    if (result.status === 'ok') applyOwnership(result.ownership);
    // A failed check never revokes ownership we already confirmed in this run.
    else setOwnershipStatus(result.status);
  };

  const initBilling = async () => {
    let result: InitResult;
    try {
      result = await billing.initialize();
    } catch {
      result = { status: 'unavailable', reason: 'init-failed' };
    }
    setBillingInit(result);
    if (result.status !== 'ready') {
      setOwnershipStatus('unavailable');
      return;
    }
    billing.onOwnershipChange((o) => {
      const before = owned();
      applyOwnership(o);
      const gained = [...o.entitlements].some((e) => !before.has(e));
      // The purchase dialog already reports its own success; this covers later approvals.
      if (gained && !busyProduct()) addNotice('info', 'Purchase confirmed — new pack unlocked.');
    });
    await refreshOwnership();
  };

  // ---------- init ----------
  const init = async () => {
    const [settingsRes, setupRes, sessionRes, decksRes] = await Promise.all([
      repo.load(settingsCodec),
      repo.load(setupCodec),
      repo.load(sessionCodec),
      repo.load(decksCodec),
    ]);
    metrics = await createMetrics(repo, now);

    if (settingsRes.status === 'ok') setSettings(settingsRes.value);
    if (decksRes.status === 'ok') setDecks(decksRes.value);

    let draft: SetupDraft =
      setupRes.status === 'ok'
        ? setupRes.value
        : { players: defaultRoster(rng, 4), packId: FREE_PACK_ID, timerSeconds: DEFAULT_TIMER };
    if (draft.players.length === 0) draft = { ...draft, players: defaultRoster(rng, 4) };
    setSetup(draft);

    if (sessionRes.status === 'ok') {
      const recovered = recoverSession(sessionRes.value, now());
      setSession(recovered);
      if (recovered !== sessionRes.value) void repo.save(sessionCodec, recovered);
      if (hasActiveRound(recovered)) setScreen('game');
    } else if (sessionRes.status === 'invalid') {
      void repo.remove(sessionCodec);
      addNotice(
        'warning',
        'A saved game could not be restored (it was damaged or from an incompatible version) and was cleared. Your settings and purchases are not affected.',
      );
    } else if (sessionRes.status === 'error') {
      addNotice('warning', 'Saved data could not be read on this device. You can still play.');
    }
    if (settingsRes.status === 'error' || setupRes.status === 'error') setStorageFailed(true);

    setReady(true);
    syncPrivacy();
    void initBilling();
  };

  // ---------- interruption handling ----------
  const cover = () => setCoverSignal((n) => n + 1);

  const interrupt = () => {
    cover();
    const s = session();
    if (!s?.round) return;
    const kind = s.round.phase.kind;
    if (kind === 'results' || kind === 'paused') return;
    const r = transition(s, { type: 'INTERRUPT', reason: 'interrupted' }, now());
    if (r.ok) commitSession(r.session);
  };

  const unsubscribers = [
    native.onBackground(() => {
      interrupt();
      void repo.flush();
    }),
    native.onBlur(cover),
    native.onConnectivityChange((isOnline) => {
      setOnline(isOnline);
      if (isOnline) void refreshOwnership();
    }),
    native.onBackButton(() => {
      void handleBack();
    }),
  ];

  const handleBack = async () => {
    if (modal()) {
      setModal(null);
      return;
    }
    const current = screen();
    if (current === 'game') {
      const s = session();
      const kind = s?.round?.phase.kind;
      if (kind && kind !== 'paused' && kind !== 'results') {
        interrupt();
        return;
      }
      goHome();
      return;
    }
    if (current === 'home') {
      await native.minimizeApp();
      return;
    }
    const h = history();
    const prev = h[h.length - 1];
    batch(() => {
      setHistory(h.slice(0, -1));
      setScreen(prev && prev !== 'game' ? prev : 'home');
    });
    syncPrivacy();
  };

  // ---------- gameplay ----------
  const ownedNow = () => owned();
  const packUnlocked = (packId: string) => isPackUnlocked(packId, ownedNow());

  const dispatch = (action: GameAction): boolean => {
    const s = session();
    if (!s) return false;
    const r = transition(s, action, now());
    if (!r.ok) return false;
    commitSession(r.session);
    const phase = r.session.round?.phase;
    if (phase?.kind === 'results' && s.round?.phase.kind !== 'results') {
      track({
        name: 'round_complete',
        outcome: phase.result.outcome,
        runoff: phase.result.runoffTally !== null,
      });
      feedback('success', 'celebrate');
    }
    return true;
  };

  /** Privacy protection is applied BEFORE any secret is mounted. */
  const openCard = async () => {
    try {
      await native.setPrivacyScreen(true);
    } catch {
      /* best effort: still allow play, documented limitation */
    }
    if (dispatch({ type: 'OPEN_CARD' })) feedback('tap');
  };

  const beginRound = async (base: Session): Promise<boolean> => {
    const packId = base.config.packId;
    const pack = getPack(packId);
    if (!pack || !packUnlocked(packId)) {
      addNotice('warning', 'That pack is locked. Choose an unlocked pack to play.');
      return false;
    }
    await native.setPrivacyScreen(true).catch(() => {});
    const { wordId, deck } = drawWord(
      decks()[packId],
      packId,
      pack.contentVersion,
      pack.entries.map((e) => e.id),
      rng,
    );
    const entry = pack.entries.find((e) => e.id === wordId);
    if (!entry) return false;
    const next = startRound(base, { wordId, word: entry.word }, rng);
    const nextDecks = { ...decks(), [packId]: deck };
    setDecks(nextDecks);
    void repo.save(decksCodec, nextDecks);
    batch(() => {
      commitSession(next);
      navigate('game', { replace: screen() === 'game' });
    });
    syncPrivacy();
    track({ name: 'round_start', packId });
    return true;
  };

  const updateSetup = (patch: Partial<SetupDraft>) => commitSetup({ ...setup(), ...patch });

  const setPlayerName = (id: string, name: string) =>
    updateSetup({ players: setup().players.map((p) => (p.id === id ? { ...p, name } : p)) });

  const addPlayer = () => {
    const players = setup().players;
    if (players.length >= MAX_PLAYERS) return;
    updateSetup({ players: [...players, createPlayer(rng, nextDefaultName(players))] });
  };

  const removePlayer = (id: string) => {
    const players = setup().players;
    if (players.length <= MIN_PLAYERS) return;
    updateSetup({ players: players.filter((p) => p.id !== id) });
  };

  const selectPack = (packId: string) => {
    if (!isPackId(packId) || !packUnlocked(packId)) return;
    updateSetup({ packId });
    track({ name: 'pack_select', packId });
  };

  /** Returns false when validation fails. May ask for confirmation first. */
  const startFromSetup = async (): Promise<boolean> => {
    const draft = setup();
    if (!validateRoster(draft.players).ok) return false;
    if (!packUnlocked(draft.packId)) return false;
    const players: Player[] = finalizeRoster(draft.players);
    const config = { packId: draft.packId, timerSeconds: draft.timerSeconds };
    const existing = session();

    const fresh = async () => {
      const s = createSession(players, config, rng, now());
      track({ name: 'session_start', players: players.length });
      return beginRound(s);
    };

    if (existing && hasActiveRound(existing)) {
      // Setup is not reachable mid-round from the UI; refuse rather than discard.
      return false;
    }
    if (existing && sameRosterIds(existing.players, players)) {
      // Same people (names may be edited): keep the score session.
      return beginRound({ ...existing, players, config, round: null });
    }
    if (existing && existing.roundsCompleted > 0) {
      setModal({
        kind: 'confirm',
        title: 'Start a new score session?',
        body: 'You changed who is playing, so scores start again from zero. The current scores will be cleared.',
        confirmLabel: 'New session',
        cancelLabel: 'Keep editing',
        onConfirm: async () => {
          setModal(null);
          await fresh();
        },
      });
      return false;
    }
    return fresh();
  };

  const playAgain = async () => {
    const s = session();
    if (!s || hasActiveRound(s)) return;
    await beginRound({ ...s, round: null });
  };

  const confirmAbandon = () => {
    setModal({
      kind: 'confirm',
      title: 'Abandon this round?',
      body: 'Nobody gets points for this round. Session scores so far are kept.',
      confirmLabel: 'Abandon round',
      cancelLabel: 'Keep playing',
      danger: true,
      onConfirm: () => {
        const s = session();
        setModal(null);
        if (!s) return;
        commitSession(abandonRound(s));
        track({ name: 'round_abandon' });
        goHome();
      },
    });
  };

  const confirmEndSession = () => {
    setModal({
      kind: 'confirm',
      title: 'End this session?',
      body: 'Scores for this group will be cleared. Your player list is kept for next time.',
      confirmLabel: 'End session',
      cancelLabel: 'Cancel',
      danger: true,
      onConfirm: () => {
        setModal(null);
        if (hasActiveRound(session())) track({ name: 'round_abandon' });
        commitSession(null);
        goHome();
      },
    });
  };

  /** From the results screen: keep roster and scores, pick another pack in setup. */
  const changePack = () => {
    const s = session();
    if (s)
      commitSetup({
        ...setup(),
        players: s.players,
        packId: s.config.packId,
        timerSeconds: s.config.timerSeconds,
      });
    navigate('setup');
  };

  // ---------- store ----------
  const loadProducts = async () => {
    if (billingInit() === null) return;
    if (billingInit()?.status !== 'ready') {
      setProducts({ status: 'unavailable', message: billingUnavailableMessage() });
      return;
    }
    setProductsLoading(true);
    try {
      setProducts(await billing.getProducts());
    } finally {
      setProductsLoading(false);
    }
  };

  const billingUnavailableMessage = () => {
    const init = billingInit();
    if (init?.status === 'unavailable') return init.message ?? 'Purchases are not available.';
    return 'Purchases are not available.';
  };

  const purchase = async (productId: ProductId) => {
    if (busyProduct()) return; // one purchase at a time; ignores double taps
    if (isProductOwned(productId, owned())) return;
    setBusyProduct(productId);
    setPurchaseNotice(null);
    track({ name: 'purchase_attempt' });
    try {
      const result = await billing.purchase(productId);
      if ((result.status === 'success' || result.status === 'already-owned') && result.ownership) {
        applyOwnership(result.ownership);
      }
      track({ name: 'purchase_result', status: result.status });
      const text =
        result.status === 'error' ||
        result.status === 'not-available' ||
        result.status === 'unavailable'
          ? result.message || PURCHASE_TEXT[result.status]
          : PURCHASE_TEXT[result.status];
      setPurchaseNotice({ productId, status: result.status, text });
      if (result.status === 'success') feedback('success', 'celebrate');
    } catch {
      setPurchaseNotice({ productId, status: 'error', text: PURCHASE_TEXT.error });
    } finally {
      setBusyProduct(null);
    }
  };

  const restore = async () => {
    if (restoring()) return;
    setRestoring(true);
    setRestoreNotice(null);
    try {
      let result: RestoreResult;
      if (billingInit()?.status !== 'ready') {
        result = { status: 'unavailable', message: billingUnavailableMessage() };
      } else {
        result = await billing.restore();
      }
      track({ name: 'restore_result', status: result.status });
      if (result.status === 'restored') {
        applyOwnership(result.ownership);
        const count = result.ownership.entitlements.size;
        setRestoreNotice(
          count === 0
            ? 'No previous pack purchases were found for this Google Play account.'
            : `Restored ${count} pack${count === 1 ? '' : 's'}.`,
        );
      } else if (result.status === 'offline') {
        setRestoreNotice('You’re offline. Connect to the internet to restore purchases.');
      } else {
        setRestoreNotice(result.message);
      }
    } finally {
      setRestoring(false);
    }
  };

  const openPaywall = (target: string) => {
    setModal({ kind: 'paywall', target });
    track({ name: 'paywall_view', packId: target });
    if (!products() && !productsLoading()) void loadProducts();
  };

  // ---------- settings / reset ----------
  const resetGameData = async () => {
    batch(() => {
      setSession(null);
      setDecks({});
    });
    await Promise.all([repo.remove(sessionCodec), repo.remove(decksCodec)]);
    syncPrivacy();
  };

  const resetEverything = async () => {
    const roster = defaultRoster(rng, 4);
    batch(() => {
      setSession(null);
      setDecks({});
      setSettings(DEFAULT_SETTINGS);
      setSetup({ players: roster, packId: FREE_PACK_ID, timerSeconds: DEFAULT_TIMER });
    });
    await Promise.all([
      repo.remove(sessionCodec),
      repo.remove(decksCodec),
      repo.remove(settingsCodec),
      repo.remove(setupCodec),
      metrics?.reset(),
    ]);
    syncPrivacy();
  };

  const dispose = () => {
    for (const u of unsubscribers) u();
  };

  return {
    // state
    ready,
    screen,
    settings,
    setup,
    session,
    decks,
    modal,
    notices,
    coverSignal,
    online,
    owned,
    ownershipStatus,
    billingInit,
    products,
    productsLoading,
    busyProduct,
    purchaseNotice,
    restoring,
    restoreNotice,
    storageFailed,
    isDemoStore: billing.isDemo,
    billing,
    now,
    // actions
    init,
    navigate,
    goHome,
    handleBack,
    setModal,
    dismissNotice,
    dispatch,
    openCard,
    feedback,
    cover,
    interrupt,
    updateSetup,
    setPlayerName,
    addPlayer,
    removePlayer,
    selectPack,
    startFromSetup,
    playAgain,
    confirmAbandon,
    confirmEndSession,
    changePack,
    packUnlocked,
    loadProducts,
    purchase,
    restore,
    openPaywall,
    refreshOwnership,
    updateSettings: commitSettings,
    resetGameData,
    resetEverything,
    metrics: () => metrics,
    flush: () => repo.flush(),
    dispose,
  };
}

export type AppController = ReturnType<typeof createAppController>;
