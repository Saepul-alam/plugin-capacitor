import { WebPlugin, registerWebPlugin } from '@capacitor/core';
import {
  PluginSecurePlugin,
  GPSVerificationResult,
  VerifiedPhotoOptions,
  VerifiedPhotoResult,
  DeviceIntegrityResult,
  BiometricAvailabilityResult,
  BiometricAuthOptions,
  BiometricAuthResult,
  BarcodeScanOptions,
  BarcodeScanResult
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

  async checkBiometricAvailability(): Promise<BiometricAvailabilityResult> {
    console.warn('[PluginSecure] Biometric authentication not available on web');
    return {
      isAvailable: false,
      biometryType: 'none',
      reason: 'Web platform does not support native biometrics'
    };
  }

  async authenticateBiometric(options?: BiometricAuthOptions): Promise<BiometricAuthResult> {
    console.warn('[PluginSecure] Biometric authentication mocked on web');
    return { success: true };
  }

  async scanBarcode(options?: BarcodeScanOptions): Promise<BarcodeScanResult> {
    const input = typeof window !== 'undefined' ? window.prompt(options?.prompt || 'Simulasi Barcode Scan (Web Fallback):', 'WPX123456789') : null;
    if (input) {
      return {
        hasContent: true,
        content: input.trim(),
        format: 'WEB_SIMULATED'
      };
    }
    return {
      hasContent: false,
      content: '',
      format: 'NONE'
    };
  }

  // Inherit addListener and removeAllListeners from WebPlugin
}

const PluginSecure = new PluginSecureWeb();

// Capacitor 2 has no registerPlugin(); registerWebPlugin() is the equivalent.
// It only fills in Plugins['PluginSecure'] when the platform entry is missing or
// when config.platforms lists the current platform, so the native implementation
// registered by MainActivity is never overwritten on Android/iOS. This exists so
// `Plugins.PluginSecure` resolves on the web/browser build instead of falling back
// to the "does not have web implementation" proxy.
registerWebPlugin(PluginSecure);

export { PluginSecure };
export * from './definitions';
