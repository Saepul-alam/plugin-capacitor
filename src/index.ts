import { registerPlugin } from '@capacitor/core';

import type { WahanaPluginPlugin } from './definitions';

const WahanaPlugin = registerPlugin<WahanaPluginPlugin>('WahanaPlugin', {
  web: () => import('./web').then((m) => new m.WahanaPluginWeb()),
});

export * from './definitions';
export { WahanaPlugin };
