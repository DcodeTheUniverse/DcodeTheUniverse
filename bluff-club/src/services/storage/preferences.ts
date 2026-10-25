import { Preferences } from '@capacitor/preferences';
import type { KeyValueStore } from './storage';

/**
 * Capacitor Preferences: SharedPreferences on Android, localStorage on the web.
 * The plugin ships a web implementation, so this works in a plain browser too.
 */
export function createPreferencesStore(): KeyValueStore {
  return {
    async get(key) {
      const { value } = await Preferences.get({ key });
      return value;
    },
    async set(key, value) {
      await Preferences.set({ key, value });
    },
    async remove(key) {
      await Preferences.remove({ key });
    },
  };
}
