/**
 * Key/value storage boundary. The app only talks to this interface; the Capacitor
 * Preferences implementation lives in ./preferences.ts and tests use memory.
 */
export interface KeyValueStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  remove(key: string): Promise<void>;
}

export function createMemoryStore(initial: Record<string, string> = {}): KeyValueStore & {
  data: Map<string, string>;
} {
  const data = new Map(Object.entries(initial));
  return {
    data,
    async get(key) {
      return data.get(key) ?? null;
    },
    async set(key, value) {
      data.set(key, value);
    },
    async remove(key) {
      data.delete(key);
    },
  };
}

/** Versioned envelope wrapped around every persisted value. */
export interface Envelope<T> {
  readonly v: number;
  readonly savedAt: number;
  readonly data: T;
}

export type LoadResult<T> =
  | { readonly status: 'missing' }
  | { readonly status: 'ok'; readonly value: T }
  | { readonly status: 'invalid'; readonly reason: string }
  | { readonly status: 'error'; readonly reason: string };

export interface Codec<T> {
  readonly key: string;
  readonly version: number;
  /** Runtime validation; throw to reject. May migrate older versions. */
  decode(data: unknown, version: number): T;
}

/**
 * Serialises writes so that a slow earlier save can never land after a newer one.
 * Writes run one at a time in call order; if several writes for the same key are
 * queued, only the newest is executed (older ones are superseded, not reordered).
 */
export class SerialWriter {
  private chain: Promise<void> = Promise.resolve();
  private readonly latest = new Map<string, number>();
  private seq = 0;
  private failures = 0;

  constructor(
    private readonly store: KeyValueStore,
    private readonly onError: (key: string, error: unknown) => void = () => {},
  ) {}

  get failureCount(): number {
    return this.failures;
  }

  write(key: string, value: string | null): Promise<void> {
    const id = ++this.seq;
    this.latest.set(key, id);
    const run = async () => {
      if (this.latest.get(key) !== id) return; // superseded by a newer write
      try {
        if (value === null) await this.store.remove(key);
        else await this.store.set(key, value);
      } catch (error) {
        this.failures++;
        this.onError(key, error);
      }
    };
    this.chain = this.chain.then(run, run);
    return this.chain;
  }

  /** Resolves when every queued write has settled. */
  flush(): Promise<void> {
    return this.chain;
  }
}

export class Repository {
  readonly writer: SerialWriter;

  constructor(
    private readonly store: KeyValueStore,
    private readonly now: () => number = Date.now,
    onError?: (key: string, error: unknown) => void,
  ) {
    this.writer = new SerialWriter(store, onError);
  }

  async load<T>(codec: Codec<T>): Promise<LoadResult<T>> {
    let raw: string | null;
    try {
      raw = await this.store.get(codec.key);
    } catch (error) {
      return { status: 'error', reason: error instanceof Error ? error.message : 'read failed' };
    }
    if (raw === null) return { status: 'missing' };
    try {
      const parsed: unknown = JSON.parse(raw);
      if (typeof parsed !== 'object' || parsed === null) throw new Error('not an envelope');
      const env = parsed as Partial<Envelope<unknown>>;
      if (typeof env.v !== 'number' || !('data' in env)) throw new Error('not an envelope');
      if (env.v > codec.version) throw new Error(`saved by a newer app version (v${env.v})`);
      return { status: 'ok', value: codec.decode(env.data, env.v) };
    } catch (error) {
      return { status: 'invalid', reason: error instanceof Error ? error.message : 'invalid data' };
    }
  }

  save<T>(codec: Codec<T>, value: T): Promise<void> {
    const envelope: Envelope<T> = { v: codec.version, savedAt: this.now(), data: value };
    return this.writer.write(codec.key, JSON.stringify(envelope));
  }

  remove(codec: Codec<unknown>): Promise<void> {
    return this.writer.write(codec.key, null);
  }

  flush(): Promise<void> {
    return this.writer.flush();
  }
}
