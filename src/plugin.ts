import { Plugins } from '@capacitor/core';
import { PluginSecurePlugin } from './definitions';
import { PluginSecureWeb } from './index';

const PluginSecure = Plugins.PluginSecure as PluginSecurePlugin || new PluginSecureWeb();

export { PluginSecure };
export * from './definitions';