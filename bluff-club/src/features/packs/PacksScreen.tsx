import { createMemo, createSignal, For, onMount, Show } from 'solid-js';
import { useApp } from '../../app/context';
import {
  Badge,
  Button,
  Card,
  Dialog,
  Notice,
  Screen,
  Segmented,
  Spinner,
} from '../../components/ui';
import { getPack, PACKS, type Pack } from '../../content';
import {
  BUNDLE_ID,
  isBundleOffered,
  isPremiumPack,
  isProductOwned,
  PRODUCTS,
  type ProductId,
  productForPack,
} from '../../services/billing/catalog';
import type { StoreProduct } from '../../services/billing/types';

/** Compiled out of production builds together with the mock store. */
export const DEMO_PURCHASE_LABEL = __MOCK_BILLING_ENABLED__ ? 'Demo purchase — no charge' : '';

function useStore() {
  const app = useApp();
  const productMap = createMemo(() => {
    const p = app.products();
    const map = new Map<ProductId, StoreProduct>();
    if (p?.status === 'ok') for (const item of p.products) map.set(item.id, item);
    return map;
  });
  const priceFor = (id: ProductId) => productMap().get(id)?.priceString ?? null;
  const owns = (id: ProductId) => isProductOwned(id, app.owned());
  return { app, productMap, priceFor, owns };
}

function StoreStatus() {
  const { app } = useStore();
  return (
    <div class="space-y-3">
      <Show when={app.isDemoStore}>
        <Notice tone="warning" title="Demo store">
          This development build uses a simulated store. {DEMO_PURCHASE_LABEL}.
        </Notice>
      </Show>
      <Show when={app.billingInit()?.status === 'unavailable'}>
        <Notice tone="info" title="Purchases unavailable">
          {(app.billingInit() as { message?: string }).message ?? 'Purchases are not available.'}{' '}
          The free General pack is always playable.
        </Notice>
      </Show>
      <Show when={!app.online() && app.billingInit()?.status === 'ready'}>
        <Notice tone="warning" title="You’re offline">
          Packs you already own keep working. Buying or restoring needs an internet connection.
        </Notice>
      </Show>
      <Show when={app.ownershipStatus() === 'error' || app.ownershipStatus() === 'offline'}>
        <Notice tone="warning">
          Couldn’t check your purchases just now. Previously confirmed packs stay unlocked while we
          retry.
          <Button
            variant="ghost"
            size="md"
            class="-ml-4"
            onClick={() => void app.refreshOwnership()}
          >
            Try again
          </Button>
        </Notice>
      </Show>
    </div>
  );
}

function PriceLine(props: { productId: ProductId }) {
  const { app, priceFor, owns } = useStore();
  return (
    <Show when={!owns(props.productId)} fallback={<Badge tone="lime">✓ Owned</Badge>}>
      <Show when={app.billingInit()?.status === 'ready'} fallback={<Badge>🔒 Locked</Badge>}>
        <Show when={!app.productsLoading()} fallback={<Spinner small label="Loading price" />}>
          <Show when={priceFor(props.productId)} fallback={<Badge>Price unavailable</Badge>}>
            {(price) => <Badge>{price()} · once-off</Badge>}
          </Show>
        </Show>
      </Show>
    </Show>
  );
}

export function PacksScreen() {
  const { app, owns } = useStore();
  onMount(() => {
    if (app.products()?.status !== 'ok') void app.loadProducts();
  });
  const premium = () => PACKS.filter((p) => p.tier === 'premium');
  const bundleOffered = () => isBundleOffered(app.owned());
  const allOwned = () => owns(BUNDLE_ID);

  return (
    <Screen title="Packs" onBack={() => app.handleBack()}>
      <div class="space-y-4">
        <StoreStatus />
        <Show when={app.products()?.status === 'error' || app.products()?.status === 'offline'}>
          <Notice tone="warning" title="Prices unavailable">
            {(app.products() as { message: string }).message}
            <Button variant="ghost" size="md" class="-ml-4" onClick={() => void app.loadProducts()}>
              Retry
            </Button>
          </Notice>
        </Show>

        <For each={PACKS.filter((p) => p.tier === 'free')}>
          {(pack) => (
            <Card>
              <div class="flex items-start justify-between gap-3">
                <h2 class="text-2xl font-black">{pack.name}</h2>
                <Badge tone="lime">Free</Badge>
              </div>
              <p class="mt-1 text-cream/90">{pack.description}</p>
              <p class="mt-2 text-muted">{pack.entries.length} words · unlimited rounds</p>
            </Card>
          )}
        </For>

        <For each={premium()}>
          {(pack) => {
            const productId = productForPack(pack.id as Parameters<typeof productForPack>[0]);
            return (
              <Card>
                <div class="flex flex-wrap items-start justify-between gap-2">
                  <h2 class="text-2xl font-black">{pack.name}</h2>
                  <PriceLine productId={productId} />
                </div>
                <p class="mt-1 text-cream/90">{pack.description}</p>
                <p class="mt-2 text-muted">
                  {pack.entries.length} words · e.g. {pack.previews.join(', ')}
                </p>
                <Button
                  variant={owns(productId) ? 'ghost' : 'secondary'}
                  block
                  class="mt-3"
                  onClick={() => app.openPaywall(pack.id)}
                >
                  {owns(productId) ? 'Pack details' : 'View pack'}
                </Button>
              </Card>
            );
          }}
        </For>

        <Show
          when={bundleOffered()}
          fallback={
            <Show when={!allOwned()}>
              <Notice tone="info" title="Party Bundle">
                You already own at least one launch pack, so the bundle isn’t offered. Buy the
                remaining packs individually — there’s no partial-price upgrade.
              </Notice>
            </Show>
          }
        >
          <Card tone="raised" class="border-2 border-lime">
            <div class="flex flex-wrap items-start justify-between gap-2">
              <h2 class="text-2xl font-black">Party Bundle</h2>
              <PriceLine productId={BUNDLE_ID} />
            </div>
            <p class="mt-1 text-cream/90">
              Exactly these four packs:{' '}
              {premium()
                .map((p) => p.name)
                .join(', ')}
              . Future packs are not included.
            </p>
            <Button block class="mt-3" onClick={() => app.openPaywall('bundle')}>
              View bundle
            </Button>
          </Card>
        </Show>

        <RestoreBlock />
        <Show when={app.isDemoStore}>
          <DemoControls />
        </Show>
      </div>
    </Screen>
  );
}

function RestoreBlock() {
  const { app } = useStore();
  return (
    <Card tone="outline">
      <h2 class="text-lg font-black">Already bought packs?</h2>
      <p class="text-muted">
        Restore purchases made with the same Google Play account, e.g. after reinstalling.
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
        {(text) => (
          <p role="status" class="mt-3 font-semibold">
            {text()}
          </p>
        )}
      </Show>
    </Card>
  );
}

function DemoControls() {
  const { app } = useStore();
  type Controls = import('../../services/billing/mock').MockBillingControls;
  type Outcome = import('../../services/billing/mock').MockOutcome;
  const controls = (app.billing as unknown as { controls?: Controls }).controls;
  const [outcome, setOutcome] = createSignal<Outcome>(controls?.nextOutcome() ?? 'success');
  // Re-evaluated whenever a purchase finishes or ownership changes.
  const hasPending = createMemo(() => {
    app.purchaseNotice();
    app.owned();
    return controls?.hasPending() ?? false;
  });
  const options = [
    { value: 'success', label: 'Success' },
    { value: 'cancel', label: 'Cancel' },
    { value: 'pending', label: 'Pending' },
    { value: 'error', label: 'Error' },
    { value: 'offline', label: 'Offline' },
  ] as const;
  return (
    <Show when={controls}>
      {(c) => (
        <Card tone="outline" class="space-y-3">
          <h2 class="text-lg font-black">Demo store controls</h2>
          <p class="text-muted">Development only. {DEMO_PURCHASE_LABEL}.</p>
          <Segmented
            label="Next demo purchase result"
            options={options}
            value={outcome()}
            onChange={(v) => {
              c().setNextOutcome(v);
              setOutcome(v);
            }}
          />
          <Button
            variant="secondary"
            block
            disabled={!hasPending()}
            onClick={() => void c().completePending()}
          >
            Approve pending demo payment
          </Button>
          <Button
            variant="secondary"
            block
            disabled={app.owned().size === 0}
            onClick={() => void c().refundAll()}
          >
            Simulate refund of all demo purchases
          </Button>
        </Card>
      )}
    </Show>
  );
}

/** The paywall: exact contents, once-off nature, live store price, close and restore. */
export function Paywall(props: { target: string }) {
  const { app, priceFor, owns } = useStore();
  const isBundle = () => props.target === 'bundle';
  const pack = () => (isBundle() ? undefined : getPack(props.target));
  const productId = (): ProductId | null => {
    if (isBundle()) return BUNDLE_ID;
    const p = pack();
    return p && isPremiumPack(p.id) ? productForPack(p.id) : null;
  };
  const includedPacks = (): Pack[] => {
    const id = productId();
    if (!id) return [];
    return PRODUCTS[id].packIds.map((pid) => getPack(pid)).filter((p): p is Pack => !!p);
  };
  const title = () => (isBundle() ? 'Party Bundle' : (pack()?.name ?? 'Pack'));
  const bundleBlocked = () => isBundle() && !isBundleOffered(app.owned()) && !owns(BUNDLE_ID);
  const price = () => (productId() ? priceFor(productId() as ProductId) : null);
  const busy = () => app.busyProduct() === productId();
  const notice = () => {
    const n = app.purchaseNotice();
    return n && n.productId === productId() ? n : null;
  };
  const owned = () => !!productId() && owns(productId() as ProductId);
  const canBuy = () =>
    !!productId() &&
    !owned() &&
    !bundleBlocked() &&
    app.billingInit()?.status === 'ready' &&
    !!price() &&
    !app.busyProduct();

  return (
    <Dialog
      title={title()}
      wide
      onClose={() => app.setModal(null)}
      footer={
        <>
          <Show
            when={!owned()}
            fallback={
              <Button block data-autofocus onClick={() => app.setModal(null)}>
                Done
              </Button>
            }
          >
            <Button
              size="xl"
              block
              busy={busy()}
              disabled={!canBuy()}
              onClick={() => {
                const id = productId();
                if (id) void app.purchase(id);
              }}
            >
              {price() ? `Buy once for ${price()}` : 'Buy'}
            </Button>
            <Show when={app.isDemoStore}>
              <p class="text-center font-bold text-danger">{DEMO_PURCHASE_LABEL}</p>
            </Show>
          </Show>
          <Button
            variant="secondary"
            block
            busy={app.restoring()}
            disabled={app.restoring()}
            onClick={() => void app.restore()}
          >
            Restore purchases
          </Button>
          <Button
            variant="ghost"
            block
            data-autofocus={owned() ? undefined : true}
            onClick={() => app.setModal(null)}
          >
            Not now
          </Button>
        </>
      }
    >
      <Show when={pack()}>{(p) => <p class="text-lg text-cream/90">{p().description}</p>}</Show>
      <ul class="space-y-2">
        <For each={includedPacks()}>
          {(p) => (
            <li class="rounded-2xl bg-surface-2 p-4">
              <div class="flex items-center justify-between gap-2">
                <span class="text-lg font-black">{p.name}</span>
                <span class="text-muted">{p.entries.length} words</span>
              </div>
              <p class="mt-1 text-muted">Examples: {p.previews.join(' · ')}</p>
            </li>
          )}
        </For>
      </ul>
      <Show when={isBundle()}>
        <p class="text-cream/90">
          Includes exactly the four packs above (
          {includedPacks().reduce((n, p) => n + p.entries.length, 0)} words). Future packs are not
          included.
        </p>
      </Show>
      <p class="font-semibold">
        Once-off purchase · no subscription · no ads. Play it offline after Google Play confirms the
        purchase.
      </p>
      <Show when={app.billingInit()?.status === 'ready'}>
        <Show
          when={!app.productsLoading()}
          fallback={<Spinner small label="Loading the current price from Google Play…" />}
        >
          <Show when={!price() && !owned()}>
            <Notice tone="warning">
              The price couldn’t be loaded, so buying is disabled.{' '}
              <Button
                variant="ghost"
                size="md"
                class="-ml-4"
                onClick={() => void app.loadProducts()}
              >
                Retry
              </Button>
            </Notice>
          </Show>
        </Show>
      </Show>
      <Show when={app.billingInit()?.status === 'unavailable'}>
        <Notice tone="info">
          {(app.billingInit() as { message?: string }).message ?? 'Purchases are unavailable.'}
        </Notice>
      </Show>
      <Show when={bundleBlocked()}>
        <Notice tone="info">
          You already own part of this bundle, so it isn’t offered. Buy the remaining packs
          individually.
        </Notice>
      </Show>
      <Show when={owned()}>
        <Notice tone="success">
          You own {isBundle() ? 'all four packs' : 'this pack'}. Enjoy!
        </Notice>
      </Show>
      <Show when={!app.online() && !owned()}>
        <Notice tone="warning">You’re offline. Buying needs an internet connection.</Notice>
      </Show>
      <Show when={notice()}>
        {(n) => (
          <Notice
            live
            tone={
              n().status === 'success' || n().status === 'already-owned'
                ? 'success'
                : n().status === 'pending' || n().status === 'cancelled'
                  ? 'info'
                  : 'error'
            }
          >
            {n().text}
          </Notice>
        )}
      </Show>
      <Show when={app.restoreNotice()}>{(t) => <p role="status">{t()}</p>}</Show>
    </Dialog>
  );
}
