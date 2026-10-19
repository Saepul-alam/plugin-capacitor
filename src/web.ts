import { WebPlugin } from '@capacitor/core';
import {
  PluginSecurePlugin,
  GPSVerificationResult,
  VerifiedPhotoOptions,
  VerifiedPhotoResult,
  DeviceIntegrityResult,
  NativeErrorOptions,
  LocationPermissionResult,
  LocationRequiredResult,
  LocationUpdate,
  LocationUpdateOptions,
  LocationConfig,
  LocationFeature,
  CameraPermissionResult,
  CameraConfig,
  CameraFeature,
  CaptureBurstOptions,
  CaptureBurstResult,
  CameraStreamOptions,
  VideoRecordingOptions,
  VideoRecordingResult
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

  async showNativeError(options?: NativeErrorOptions): Promise<void> {
    console.warn('[PluginSecure] showNativeError called on web:', options);
    if (typeof window !== 'undefined' && options) {
      alert(`${options.title || 'Error'}\n\n${options.message || 'Halaman tidak dapat dimuat'}\n(Code: ${options.code || 'UNKNOWN'})`);
    }
  }

  async hideNativeError(): Promise<void> {
    console.log('[PluginSecure] hideNativeError called on web');
  }

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

  private locationConfig: LocationConfig = { locationWatch: true };

  async getLocationConfig(): Promise<LocationConfig> {
    return { ...this.locationConfig };
  }

  async setLocationFeature(options: { feature: LocationFeature; enabled: boolean }): Promise<LocationConfig> {
    if (options && options.feature === 'locationWatch') {
      this.locationConfig.locationWatch = !!options.enabled;
    }
    return { ...this.locationConfig };
  }

  private webWatchId: number | null = null;

  async getCurrentLocation(): Promise<LocationUpdate> {
    return new Promise<LocationUpdate>((resolve, reject) => {
      if (typeof navigator === 'undefined' || !navigator.geolocation) {
        reject(new Error('Geolocation is not available on web'));
        return;
      }
      navigator.geolocation.getCurrentPosition(
        (pos) => resolve(this.toLocationUpdate(pos)),
        (err) => reject(err),
        { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }
      );
    });
  }

  async startLocationUpdates(options?: LocationUpdateOptions): Promise<void> {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      console.warn('[PluginSecure] startLocationUpdates() not available on web');
      return;
    }
    this.stopWebWatch();
    this.webWatchId = navigator.geolocation.watchPosition(
      (pos) => this.notifyListeners('locationUpdate', this.toLocationUpdate(pos)),
      (err) => console.warn('[PluginSecure] location watch error:', err),
      { enableHighAccuracy: options?.highAccuracy !== false, timeout: 15000, maximumAge: 0 }
    );
  }

  async stopLocationUpdates(): Promise<void> {
    this.stopWebWatch();
  }

  private stopWebWatch(): void {
    if (this.webWatchId != null && typeof navigator !== 'undefined' && navigator.geolocation) {
      navigator.geolocation.clearWatch(this.webWatchId);
    }
    this.webWatchId = null;
  }

  private toLocationUpdate(pos: GeolocationPosition): LocationUpdate {
    const c = pos.coords;
    return {
      latitude: c.latitude,
      longitude: c.longitude,
      altitude: c.altitude,
      accuracy: c.accuracy,
      altitudeAccuracy: c.altitudeAccuracy,
      speed: c.speed,
      heading: c.heading,
      timestamp: pos.timestamp,
      provider: 'web',
      ageMs: Math.max(0, Date.now() - pos.timestamp),
      isMock: false
    };
  }

  async checkCameraPermission(): Promise<CameraPermissionResult> {
    if (typeof navigator !== 'undefined' && navigator.mediaDevices) {
      try {
        const status: any = await (navigator as any).permissions?.query?.({ name: 'camera' });
        if (status && status.state) {
          return { granted: status.state === 'granted', state: status.state };
        }
      } catch (e) { /* Permissions API unsupported for camera */ }
      return { granted: false, state: 'prompt' };
    }
    return { granted: false, state: 'denied' };
  }

  async requestCameraPermission(): Promise<CameraPermissionResult> {
    if (typeof navigator !== 'undefined' && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ video: true });
        stream.getTracks().forEach((track) => track.stop());
        return { granted: true, state: 'granted' };
      } catch (e) {
        return { granted: false, state: 'denied' };
      }
    }
    return { granted: false, state: 'denied' };
  }

  async openAppSettings(): Promise<void> {
    console.warn('[PluginSecure] openAppSettings() not available on web');
  }

  async openLocationSettings(): Promise<void> {
    console.warn('[PluginSecure] openLocationSettings() not available on web');
  }

  private cameraFlags: CameraConfig = {
    cameraOverride: true,
    cameraStream: true,
    captureBurst: true,
    videoRecorder: true,
    watermark: true
  };

  async getCameraConfig(): Promise<CameraConfig> {
    return { ...this.cameraFlags };
  }

  async setCameraFeature(options: { feature: CameraFeature; enabled: boolean }): Promise<CameraConfig> {
    if (options && options.feature in this.cameraFlags) {
      (this.cameraFlags as any)[options.feature] = !!options.enabled;
    }
    return { ...this.cameraFlags };
  }

  async captureBurst(options?: CaptureBurstOptions): Promise<CaptureBurstResult> {
    console.warn('[PluginSecure] captureBurst() has no web implementation; returning empty set');
    return { frames: [], exif: { latitude: 0, longitude: 0, altitude: 0, accuracy: -1, timestamp: Date.now(), isVerified: false, verificationDetails: 'WEB_UNAVAILABLE' } };
  }

  async startCameraStream(options?: CameraStreamOptions): Promise<{ started: boolean }> {
    console.warn('[PluginSecure] startCameraStream() not available on web');
    return { started: false };
  }

  async stopCameraStream(): Promise<void> {}

  async setCameraTorch(options: { enabled: boolean }): Promise<void> {
    console.warn('[PluginSecure] setCameraTorch() not available on web');
  }

  async startVideoRecording(options?: VideoRecordingOptions): Promise<{ started: boolean }> {
    throw new Error('Native video recording requires Android/iOS.');
  }

  async stopVideoRecording(): Promise<VideoRecordingResult> {
    throw new Error('Native video recording requires Android/iOS.');
  }
}
