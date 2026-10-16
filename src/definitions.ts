import { PluginListenerHandle } from '@capacitor/core';

export type CameraDirection = 'rear' | 'front';
export type CameraResultType = 'uri' | 'base64' | 'dataUrl';

export interface GPSVerificationResult {
  isMock: boolean;
  isJailbroken: boolean;
  details: {
    mockLocationEnabled: boolean;
    suspiciousApps: string[];
    locationProvider: string;
    accuracy: number;
    timestamp: number;
    isSimulator: boolean;
    rootPathsFound?: string[];
    jailbreakPathsFound?: string[];
  };
}

export interface VerifiedPhotoOptions {
  quality?: number;
  direction?: CameraDirection;
  requireGPSVerification?: boolean;
  maxGPSAgeMs?: number;
  minAccuracyMeters?: number;
  embedEXIF?: boolean;
  watermark?: boolean;
  resultType?: CameraResultType;
  allowFallback?: boolean;
}

export interface VerifiedPhotoResult {
  uri: string;
  base64?: string;
  dataUrl?: string;
  exif: {
    latitude: number;
    longitude: number;
    altitude: number;
    accuracy: number;
    timestamp: number;
    isVerified: boolean;
    verificationDetails: string;
  };
  path?: string;
}

export interface DeviceIntegrityResult {
  isRooted: boolean;
  isJailbroken: boolean;
  details: string[];
}

export interface BiometricAvailabilityResult {
  isAvailable: boolean;
  biometryType: 'fingerprint' | 'face' | 'iris' | 'none';
  reason?: string;
}

export interface BiometricAuthOptions {
  title?: string;
  subtitle?: string;
  description?: string;
  cancelButtonText?: string;
}

export interface BiometricAuthResult {
  success: boolean;
  error?: string;
}

export interface BarcodeScanOptions {
  prompt?: string;
  showTorchButton?: boolean;
  formats?: string[];
}

export interface BarcodeScanResult {
  hasContent: boolean;
  content: string;
  format: string;
}

export interface NativeErrorOptions {
  title?: string;
  message?: string;
  code?: string | number;
  canRetry?: boolean;
}

export interface AppStateData {
  lastUrl?: string | null;
  lastRoute?: string | null;
  timestamp?: number;
  extraData?: string | null;
  isExpired?: boolean;
}

export interface StatePreservationOptions {
  enabled?: boolean;
  maxAgeMinutes?: number;
}

export type LocationPermissionState = 'granted' | 'denied' | 'prompt';

export interface LocationPermissionResult {
  granted: boolean;
  state: LocationPermissionState | string;
  required?: boolean;
}

export interface LocationRequiredResult {
  enabled: boolean;
}

export interface PluginSecurePlugin {
  verifyGPS(): Promise<GPSVerificationResult>;
  startGPSMonitoring(options?: { intervalMs?: number }): Promise<void>;
  stopGPSMonitoring(): Promise<void>;
  captureVerifiedPhoto(options: VerifiedPhotoOptions): Promise<VerifiedPhotoResult>;
  checkDeviceIntegrity(): Promise<DeviceIntegrityResult>;
  checkBiometricAvailability(): Promise<BiometricAvailabilityResult>;
  authenticateBiometric(options?: BiometricAuthOptions): Promise<BiometricAuthResult>;
  scanBarcode(options?: BarcodeScanOptions): Promise<BarcodeScanResult>;
  showNativeError(options?: NativeErrorOptions): Promise<void>;
  hideNativeError(): Promise<void>;
  saveAppState(options: { url?: string; route?: string; data?: any }): Promise<void>;
  getLastAppState(): Promise<AppStateData>;
  enableStatePreservation(options?: { enabled: boolean }): Promise<void>;
  restoreLastAppState(): Promise<{ restored: boolean }>;
  enableNativeFeel(): Promise<void>;
  checkLocationPermission(): Promise<LocationPermissionResult>;
  requestLocationPermission(): Promise<LocationPermissionResult>;
  isLocationRequired(): Promise<LocationRequiredResult>;
  setLocationRequired(options: { enabled: boolean }): Promise<LocationRequiredResult>;
  openAppSettings(): Promise<void>;
  openLocationSettings(): Promise<void>;
  // Capacitor 3's WebPlugin returns a promise that is also a handle, and
  // removeAllListeners() returns a Promise (core/web-plugin.d.ts).
  addListener(event: 'gpsMockDetected', callback: (data: GPSVerificationResult) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'deviceIntegrityChanged', callback: (data: DeviceIntegrityResult) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'nativeErrorRetry', callback: () => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'locationPermissionChanged', callback: (data: { granted: boolean }) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  removeAllListeners(): Promise<void>;
}

export const PluginSecure = 'PluginSecure';