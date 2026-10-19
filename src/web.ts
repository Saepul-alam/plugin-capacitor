import { WebPlugin } from '@capacitor/core';

import type { WahanaPluginPlugin } from './definitions';

export class WahanaPluginWeb extends WebPlugin implements WahanaPluginPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
