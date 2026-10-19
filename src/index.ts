import { registerPlugin } from '@capacitor/core';

import type { WahanaPluginPlugin } from './definitions';

const WahanaPlugin = registerPlugin<WahanaPluginPlugin>('WahanaPlugin', {
  web: () => import('./web').then((m) => new m.WahanaPluginWeb()),
});

const WahanaGps = WahanaPlugin;

export * from './definitions';
export { WahanaPlugin, WahanaGps };
