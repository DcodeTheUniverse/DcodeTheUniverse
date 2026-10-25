import { describe, expect, it } from 'vitest';
import { settingsCodec } from '../../src/services/storage/codecs';
import {
  createMemoryStore,
  type KeyValueStore,
  Repository,
  SerialWriter,
} from '../../src/services/storage/storage';

/** A store whose writes complete in a caller-controlled (possibly reversed) order. */
function slowStore() {
  const data = new Map<string, string>();
  const pending: { key: string; value: string; resolve: () => void }[] = [];
  const store: KeyValueStore = {
    async get(key) {
      return data.get(key) ?? null;
    },
    set(key, value) {
      return new Promise<void>((resolve) => {
        pending.push({
          key,
          value,
          resolve: () => {
            data.set(key, value);
            resolve();
          },
        });
      });
    },
    async remove(key) {
      data.delete(key);
    },
  };
  return { store, data, pending };
}

describe('SerialWriter', () => {
  it('never lets an older in-flight write land after a newer one', async () => {
    const { store, data, pending } = slowStore();
    const writer = new SerialWriter(store);
    void writer.write('k', 'old');
    await new Promise((r) => setTimeout(r, 0));
    expect(pending).toHaveLength(1); // 'old' is in flight
    void writer.write('k', 'new');
    await new Promise((r) => setTimeout(r, 0));
    // Writes run one at a time: 'new' waits for 'old' instead of racing it.
    expect(pending).toHaveLength(1);
    pending[0]!.resolve();
    await new Promise((r) => setTimeout(r, 0));
    expect(pending).toHaveLength(2);
    pending[1]!.resolve();
    await writer.flush();
    expect(data.get('k')).toBe('new');
  });

  it('skips superseded writes that have not started yet', async () => {
    const { store, data, pending } = slowStore();
    const writer = new SerialWriter(store);
    void writer.write('a', '1');
    void writer.write('b', 'x');
    void writer.write('b', 'y');
    void writer.write('b', 'z');
    await new Promise((r) => setTimeout(r, 0));
    pending[0]!.resolve();
    await new Promise((r) => setTimeout(r, 0));
    pending[1]!.resolve();
    await writer.flush();
    expect(pending.map((p) => p.value)).toEqual(['1', 'z']);
    expect(data.get('b')).toBe('z');
  });

  it('keeps going after a failed write and reports it', async () => {
    const errors: string[] = [];
    const store = createMemoryStore();
    let fail = true;
    const flaky: KeyValueStore = {
      ...store,
      async set(key, value) {
        if (fail) {
          fail = false;
          throw new Error('disk full');
        }
        await store.set(key, value);
      },
    };
    const writer = new SerialWriter(flaky, (key) => errors.push(key));
    await writer.write('a', '1');
    await writer.write('b', '2');
    expect(errors).toEqual(['a']);
    expect(writer.failureCount).toBe(1);
    expect(store.data.get('b')).toBe('2');
  });
});

describe('Repository envelopes', () => {
  it('round-trips with a version and timestamp', async () => {
    const store = createMemoryStore();
    const repo = new Repository(store, () => 42);
    await repo.save(settingsCodec, {
      sound: false,
      haptics: true,
      revealMode: 'tap',
      tutorialSeen: true,
    });
    expect(JSON.parse(store.data.get('bc.settings')!)).toMatchObject({ v: 1, savedAt: 42 });
    const loaded = await repo.load(settingsCodec);
    expect(loaded).toEqual({
      status: 'ok',
      value: { sound: false, haptics: true, revealMode: 'tap', tutorialSeen: true },
    });
  });

  it('reports missing, invalid, future-version and read-error states without throwing', async () => {
    const store = createMemoryStore({
      'bc.settings': '{not json',
    });
    const repo = new Repository(store);
    expect((await repo.load(settingsCodec)).status).toBe('invalid');
    await store.set('bc.settings', JSON.stringify({ v: 99, savedAt: 1, data: {} }));
    const future = await repo.load(settingsCodec);
    expect(future.status).toBe('invalid');
    await store.remove('bc.settings');
    expect((await repo.load(settingsCodec)).status).toBe('missing');
    const broken = new Repository({ ...store, get: async () => Promise.reject(new Error('io')) });
    expect((await broken.load(settingsCodec)).status).toBe('error');
  });
});
