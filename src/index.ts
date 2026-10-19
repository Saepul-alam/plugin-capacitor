import { registerPlugin } from '@capacitor/core';
import { PluginSecurePlugin } from './definitions';

const PluginSecure = registerPlugin<PluginSecurePlugin>('PluginSecure', {
  web: () => import('./web').then(m => new m.PluginSecureWeb())
});

export { PluginSecure };
export * from './definitions';
