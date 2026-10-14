import { registerPlugin } from '@capacitor/core';
import { PluginSecurePlugin } from './definitions';
import { PluginSecureWeb } from './index';

const PluginSecure = registerPlugin<PluginSecurePlugin>('PluginSecure', {
  web: () => new PluginSecureWeb()
});

export { PluginSecure };
export * from './definitions';