/**
 * Boundary for every native-only capability. The UI depends on this interface;
 * `createCapacitorNative` wires real plugins and degrades to browser APIs (or no-ops)
 * so the app runs in a normal browser without Capacitor plugin errors.
 */
export type Unsubscribe = () => void;

export interface NativeBridge {
  readonly platform: 'android' | 'ios' | 'web';
  readonly isNative: boolean;
  /** App went to background / was hidden: cover secrets, pause timers. */
  onBackground(listener: () => void): Unsubscribe;
  /** Window lost focus (e.g. a system dialog): cover secrets only. */
  onBlur(listener: () => void): Unsubscribe;
  /** Android hardware/gesture back. Registering disables the default behaviour. */
  onBackButton(listener: () => void): Unsubscribe;
  /** Connectivity hints for purchase UI (never used to gate gameplay). */
  onConnectivityChange(listener: (online: boolean) => void): Unsubscribe;
  isOnline(): boolean;
  /** FLAG_SECURE on Android: blocks screenshots/recording and blanks recent-app previews. */
  setPrivacyScreen(enabled: boolean): Promise<void>;
  haptic(kind: 'tap' | 'success' | 'warning'): Promise<void>;
  minimizeApp(): Promise<void>;
}

export function createNoopNative(overrides: Partial<NativeBridge> = {}): NativeBridge {
  const none = () => () => {};
  return {
    platform: 'web',
    isNative: false,
    onBackground: none,
    onBlur: none,
    onBackButton: none,
    onConnectivityChange: none,
    isOnline: () => true,
    setPrivacyScreen: async () => {},
    haptic: async () => {},
    minimizeApp: async () => {},
    ...overrides,
  };
}
