import { createContext, useContext } from 'solid-js';
import type { AppController } from './controller';

export const AppContext = createContext<AppController>();

export function useApp(): AppController {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp must be used inside <AppContext.Provider>');
  return ctx;
}
