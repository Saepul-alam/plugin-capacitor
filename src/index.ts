import * as core from '@capacitor/core';
const { WebPlugin } = core;
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
  BarcodeScanResult,
  NativeErrorOptions,
  AppStateData
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

  async showNativeError(options?: NativeErrorOptions): Promise<void> {
    console.warn('[PluginSecure] showNativeError called on web:', options);
    if (typeof window !== 'undefined' && options) {
      alert(`${options.title || 'Error'}\n\n${options.message || 'Halaman tidak dapat dimuat'}\n(Code: ${options.code || 'UNKNOWN'})`);
    }
  }

  async hideNativeError(): Promise<void> {
    console.log('[PluginSecure] hideNativeError called on web');
  }

  async saveAppState(options: { url?: string; route?: string; data?: any }): Promise<void> {
    if (typeof window !== 'undefined' && window.sessionStorage) {
      sessionStorage.setItem('al_last_route', options.route || window.location.pathname);
      sessionStorage.setItem('al_last_url', options.url || window.location.href);
      sessionStorage.setItem('al_last_timestamp', String(Date.now()));
    }
  }

  async getLastAppState(): Promise<AppStateData> {
    if (typeof window !== 'undefined' && window.sessionStorage) {
      return {
        lastRoute: sessionStorage.getItem('al_last_route'),
        lastUrl: sessionStorage.getItem('al_last_url'),
        timestamp: Number(sessionStorage.getItem('al_last_timestamp')) || Date.now(),
        isExpired: false
      };
    }
    return { isExpired: true };
  }

  async enableStatePreservation(options?: { enabled: boolean }): Promise<void> {}

  async restoreLastAppState(): Promise<{ restored: boolean }> {
    return { restored: false };
  }

  async enableNativeFeel(): Promise<void> {}

  // Inherit addListener and removeAllListeners from WebPlugin
}

const coreAny = core as any;
let PluginSecureInstance: PluginSecurePlugin;

if (typeof coreAny.registerPlugin === 'function') {
  // Capacitor 3+
  PluginSecureInstance = coreAny.registerPlugin('PluginSecure', {
    web: () => new PluginSecureWeb()
  });
} else {
  // Capacitor 2 fallback
  PluginSecureInstance = new PluginSecureWeb();
  if (typeof coreAny.registerWebPlugin === 'function') {
    coreAny.registerWebPlugin(PluginSecureInstance);
  }
}

const PluginSecure = PluginSecureInstance;

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

