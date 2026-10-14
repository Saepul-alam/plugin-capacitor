import { WebPlugin, PluginListenerHandle, ListenerCallback } from '@capacitor/core';
import {
  PluginSecurePlugin,
  GPSVerificationResult,
  VerifiedPhotoOptions,
  VerifiedPhotoResult,
  DeviceIntegrityResult
} from './definitions';

export class PluginSecureWeb extends WebPlugin implements PluginSecurePlugin {
  constructor() {
    super({ name: 'PluginSecure', platforms: ['web'] });
  }

  async verifyGPS(): Promise<GPSVerificationResult> {
    console.warn('[PluginSecure] verifyGPS() not available on web');
    return {
      isMock: false,
      isJailbroken: false,
      details: {
        mockLocationEnabled: false,
        suspiciousApps: [],
        locationProvider: 'web',
        accuracy: -1,
        timestamp: Date.now(),
        isSimulator: false
      }
    };
  }

  async checkDeviceIntegrity(): Promise<DeviceIntegrityResult> {
    return { isRooted: false, isJailbroken: false, details: ['Web platform'] };
  }

  async captureVerifiedPhoto(options: VerifiedPhotoOptions): Promise<VerifiedPhotoResult> {
    throw new Error('Verified camera capture requires native platform (Android/iOS). Use @capacitor/camera for web fallback.');
  }

  async startGPSMonitoring(): Promise<void> {
    console.warn('[PluginSecure] GPS monitoring not available on web');
  }

  async stopGPSMonitoring(): Promise<void> {}

  addListener(eventName: string, listenerFunc: ListenerCallback): PluginListenerHandle {
    return super.addListener(eventName, listenerFunc);
  }

  removeAllListeners(): void {
    super.removeAllListeners();
  }
}

const PluginSecure = new PluginSecureWeb();
export { PluginSecure };