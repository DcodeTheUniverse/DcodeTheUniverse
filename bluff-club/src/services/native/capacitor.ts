import { App } from '@capacitor/app';
import { Capacitor, type PluginListenerHandle, registerPlugin } from '@capacitor/core';
import { Haptics, ImpactStyle, NotificationType } from '@capacitor/haptics';
import type { NativeBridge, Unsubscribe } from './native';

/** Small native bridge implemented in android/app/src/main/java/.../PrivacyScreenPlugin.java */
interface PrivacyScreenPlugin {
  enable(): Promise<void>;
  disable(): Promise<void>;
}

const PrivacyScreen = registerPlugin<PrivacyScreenPlugin>('PrivacyScreen');

function nativeListener(register: () => Promise<PluginListenerHandle>): Unsubscribe {
  let handle: PluginListenerHandle | null = null;
  let removed = false;
  register()
    .then((h) => {
      handle = h;
      if (removed) void h.remove();
    })
    .catch(() => {
      /* plugin unavailable: nothing to unsubscribe */
    });
  return () => {
    removed = true;
    if (handle) void handle.remove();
  };
}

export function createCapacitorNative(): NativeBridge {
  const platform = Capacitor.getPlatform() as NativeBridge['platform'];
  const isNative = Capacitor.isNativePlatform();
  const canPrivacy = isNative && Capacitor.isPluginAvailable('PrivacyScreen');
  let privacyEnabled = false;

  return {
    platform,
    isNative,

    onBackground(listener) {
      const onVisibility = () => {
        if (document.visibilityState === 'hidden') listener();
      };
      document.addEventListener('visibilitychange', onVisibility);
      window.addEventListener('pagehide', listener);
      const offPause = isNative
        ? nativeListener(() => App.addListener('pause', listener))
        : () => {};
      const offState = isNative
        ? nativeListener(() =>
            App.addListener('appStateChange', ({ isActive }) => {
              if (!isActive) listener();
            }),
          )
        : () => {};
      return () => {
        document.removeEventListener('visibilitychange', onVisibility);
        window.removeEventListener('pagehide', listener);
        offPause();
        offState();
      };
    },

    onBlur(listener) {
      window.addEventListener('blur', listener);
      return () => window.removeEventListener('blur', listener);
    },

    onBackButton(listener) {
      if (!isNative) return () => {};
      return nativeListener(() => App.addListener('backButton', () => listener()));
    },

    onConnectivityChange(listener) {
      const on = () => listener(true);
      const off = () => listener(false);
      window.addEventListener('online', on);
      window.addEventListener('offline', off);
      return () => {
        window.removeEventListener('online', on);
        window.removeEventListener('offline', off);
      };
    },

    isOnline: () => (typeof navigator === 'undefined' ? true : navigator.onLine),

    async setPrivacyScreen(enabled) {
      if (!canPrivacy || enabled === privacyEnabled) return;
      if (enabled) await PrivacyScreen.enable();
      else await PrivacyScreen.disable();
      privacyEnabled = enabled;
    },

    async haptic(kind) {
      try {
        if (kind === 'tap') await Haptics.impact({ style: ImpactStyle.Light });
        else
          await Haptics.notification({
            type: kind === 'success' ? NotificationType.Success : NotificationType.Warning,
          });
      } catch {
        /* haptics are optional */
      }
    },

    async minimizeApp() {
      if (isNative) await App.minimizeApp();
    },
  };
}
