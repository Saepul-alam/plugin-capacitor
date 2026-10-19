import { Capacitor } from '@capacitor/core';

import type { WahanaPluginPlugin } from './definitions';

function createPlugin(): WahanaPluginPlugin {
  // 1. Capacitor 3+
  if (typeof (Capacitor as any)?.registerPlugin === 'function') {
    return (Capacitor as any).registerPlugin('WahanaPlugin', {
      web: () => import('./web').then((m) => new m.WahanaPluginWeb()),
    });
  }

  // 2. Capacitor 2
  const cap = Capacitor as any;
  if (typeof cap?.registerWebPlugin === 'function') {
    import('./web').then((m) => {
      cap.registerWebPlugin(new m.WahanaPluginWeb());
    });
  }

  return new Proxy({} as WahanaPluginPlugin, {
    get(_target, prop) {
      const plugin =
        cap?.Plugins?.WahanaPlugin ||
        (typeof window !== 'undefined' && (window as any)?.Capacitor?.Plugins?.WahanaPlugin);

      if (plugin && typeof plugin[prop] === 'function') {
        return plugin[prop].bind(plugin);
      }

      return (...args: any[]) =>
        import('./web').then((m) => {
          const webInstance = new m.WahanaPluginWeb();
          if (typeof (webInstance as any)[prop] === 'function') {
            return (webInstance as any)[prop](...args);
          }
          throw new Error(`Method ${String(prop)} is not implemented on WahanaPlugin`);
        });
    },
  });
}

const WahanaPlugin = createPlugin();
const WahanaGps = WahanaPlugin;

export * from './definitions';
export { WahanaPlugin, WahanaGps };
