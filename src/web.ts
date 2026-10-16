import { WebPlugin } from '@capacitor/core';
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
  AppStateData,
  LocationPermissionResult,
  LocationRequiredResult
} from './definitions';

export class PluginSecureWeb extends WebPlugin implements PluginSecurePlugin {
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
    console.warn('[PluginSecure] authenticateBiometric mocked on web');
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

  async checkLocationPermission(): Promise<LocationPermissionResult> {
    if (typeof navigator !== 'undefined' && navigator.geolocation) {
      return { granted: true, state: 'granted', required: false };
    }
    return { granted: false, state: 'denied', required: false };
  }

  async requestLocationPermission(): Promise<LocationPermissionResult> {
    return new Promise<LocationPermissionResult>((resolve) => {
      if (typeof navigator === 'undefined' || !navigator.geolocation) {
        resolve({ granted: false, state: 'denied', required: false });
        return;
      }
      navigator.geolocation.getCurrentPosition(
        () => resolve({ granted: true, state: 'granted', required: false }),
        () => resolve({ granted: false, state: 'denied', required: false }),
        { enableHighAccuracy: false, timeout: 10000, maximumAge: 0 }
      );
    });
  }

  async isLocationRequired(): Promise<LocationRequiredResult> {
    return { enabled: false };
  }

  async setLocationRequired(options: { enabled: boolean }): Promise<LocationRequiredResult> {
    return { enabled: !!options?.enabled };
  }

  async openAppSettings(): Promise<void> {
    console.warn('[PluginSecure] openAppSettings() not available on web');
  }

  async openLocationSettings(): Promise<void> {
    console.warn('[PluginSecure] openLocationSettings() not available on web');
  }
}
