import { render } from 'solid-js/web';
import './styles/app.css';
import { App } from './app/App';
import { createAppController } from './app/controller';
import { createRandomSource } from './app/random';
import { createBillingAdapter } from './services/billing';
import { createCapacitorNative } from './services/native/capacitor';
import { createPreferencesStore } from './services/storage/preferences';

async function boot() {
  const root = document.getElementById('root');
  if (!root) return;
  const native = createCapacitorNative();
  const store = createPreferencesStore();
  const billing = await createBillingAdapter(native, store);
  const controller = createAppController({
    store,
    native,
    billing,
    rng: createRandomSource(),
    now: Date.now,
  });
  render(() => <App controller={controller} />, root);
}

void boot();
