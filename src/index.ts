import { registerPlugin } from '@capacitor/core';
import { PluginSecurePlugin, AppStateData } from './definitions';

const PluginSecure = registerPlugin<PluginSecurePlugin>('PluginSecure', {
  web: () => import('./web').then(m => new m.PluginSecureWeb())
});

/**
 * Helper to automatically sync routing & state on app switch / visibilitychange.
 * Can be hooked with Vue Router (Quasar) or standard navigation.
 */
export function setupAutoStatePreservation(options?: {
  router?: { currentRoute?: any; push?: (r: any) => void; replace?: (r: any) => void };
  onRestore?: (savedState: AppStateData) => void;
}) {
  if (typeof window === 'undefined') return;

  const saveCurrent = () => {
    const route = options?.router?.currentRoute?.fullPath || window.location.hash || window.location.pathname;
    PluginSecure.saveAppState({
      url: window.location.href,
      route: route
    }).catch(() => {});
  };

  // Sync on tab switch, minimize, or backgrounding
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      saveCurrent();
    }
  });

  window.addEventListener('beforeunload', saveCurrent);
  window.addEventListener('pagehide', saveCurrent);

  // Attempt auto-restore on initial setup if custom callback provided
  if (options?.onRestore) {
    PluginSecure.getLastAppState().then(saved => {
      if (saved && !saved.isExpired && (saved.lastRoute || saved.lastUrl)) {
        options.onRestore!(saved);
      }
    }).catch(() => {});
  }
}

export { PluginSecure };
export * from './definitions';
