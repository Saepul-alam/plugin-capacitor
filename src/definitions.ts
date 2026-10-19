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

export interface CameraConfig {
  cameraOverride: boolean;
  cameraStream: boolean;
  captureBurst: boolean;
  videoRecorder: boolean;
  watermark: boolean;
}

export type CameraFeature =
  | 'cameraOverride'
  | 'cameraStream'
  | 'captureBurst'
  | 'videoRecorder'
  | 'watermark';

export interface BurstFrame {
  base64: string;
  timestamp: number;
  exif: VerifiedPhotoResult['exif'];
}

export interface CaptureBurstOptions {
  count?: number;
  intervalMs?: number;
  poses?: string[];
  direction?: CameraDirection;
  quality?: number;
  resultType?: CameraResultType;
}

export interface CaptureBurstResult {
  frames: BurstFrame[];
  exif: VerifiedPhotoResult['exif'];
}

export interface CameraStreamOptions {
  facingMode?: 'user' | 'environment';
  width?: number;
  height?: number;
  fps?: number;
  quality?: number;
  torch?: boolean;
}

export interface CameraStreamStartResult {
  started: boolean;
  /** Degrees the frame must be rotated clockwise to appear upright (0/90/180/270). */
  rotation?: number;
  /** Frame width after applying orientation (swapped for 90/270). */
  width?: number;
  /** Frame height after applying orientation (swapped for 90/270). */
  height?: number;
}

export interface CameraFrame {
  base64: string;
  timestamp: number;
  /** Degrees the frame must be rotated clockwise to appear upright (0/90/180/270). */
  rotation?: number;
  frameWidth?: number;
  frameHeight?: number;
  facingMode?: string;
  /** Whether the active camera has a flash unit usable as a torch. */
  torch?: boolean;
}

export interface VideoRecordingOptions {
  facingMode?: 'user' | 'environment';
  quality?: 'low' | 'medium' | 'high';
  maxDurationMs?: number;
  watermark?: boolean;
}

export interface VideoRecordingResult {
  uri: string;
  path?: string;
  base64?: string;
  dataUrl?: string;
  durationMs: number;
  hasAudio: boolean;
}

export interface NativeErrorOptions {
  title?: string;
  message?: string;
  code?: string | number;
  canRetry?: boolean;
}

export type LocationPermissionState = 'granted' | 'denied' | 'prompt';

export interface LocationPermissionResult {
  granted: boolean;
  state: LocationPermissionState | string;
  required?: boolean;
}

export interface LocationUpdate {
  latitude: number;
  longitude: number;
  altitude: number | null;
  accuracy: number;
  altitudeAccuracy?: number | null;
  speed: number | null;
  heading: number | null;
  timestamp: number;
  provider: string;
  ageMs: number;
  isMock: boolean;
}

export interface LocationUpdateOptions {
  /** Update interval in milliseconds. Default 2000. */
  intervalMs?: number;
  /** Minimum movement (meters) before an update is emitted. Default 0. */
  distanceFilter?: number;
  /** Request high accuracy / GPS provider. Default true. */
  highAccuracy?: boolean;
}

export interface LocationConfig {
  /** When true the boot file bridges navigator.geolocation to the native realtime stream. */
  locationWatch: boolean;
}

export type LocationFeature = 'locationWatch';

export interface LocationRequiredResult {
  enabled: boolean;
}

export type CameraPermissionState = 'granted' | 'denied' | 'prompt';

export interface CameraPermissionResult {
  granted: boolean;
  state: CameraPermissionState | string;
}

export interface PluginSecurePlugin {
  verifyGPS(): Promise<GPSVerificationResult>;
  startGPSMonitoring(options?: { intervalMs?: number }): Promise<void>;
  stopGPSMonitoring(): Promise<void>;
  captureVerifiedPhoto(options: VerifiedPhotoOptions): Promise<VerifiedPhotoResult>;
  checkDeviceIntegrity(): Promise<DeviceIntegrityResult>;
  showNativeError(options?: NativeErrorOptions): Promise<void>;
  hideNativeError(): Promise<void>;
  checkLocationPermission(): Promise<LocationPermissionResult>;
  requestLocationPermission(): Promise<LocationPermissionResult>;
  getCurrentLocation(): Promise<LocationUpdate>;
  startLocationUpdates(options?: LocationUpdateOptions): Promise<void>;
  stopLocationUpdates(): Promise<void>;
  isLocationRequired(): Promise<LocationRequiredResult>;
  setLocationRequired(options: { enabled: boolean }): Promise<LocationRequiredResult>;
  getLocationConfig(): Promise<LocationConfig>;
  setLocationFeature(options: { feature: LocationFeature; enabled: boolean }): Promise<LocationConfig>;
  checkCameraPermission(): Promise<CameraPermissionResult>;
  requestCameraPermission(): Promise<CameraPermissionResult>;
  openAppSettings(): Promise<void>;
  openLocationSettings(): Promise<void>;
  getCameraConfig(): Promise<CameraConfig>;
  setCameraFeature(options: { feature: CameraFeature; enabled: boolean }): Promise<CameraConfig>;
  captureBurst(options?: CaptureBurstOptions): Promise<CaptureBurstResult>;
  startCameraStream(options?: CameraStreamOptions): Promise<CameraStreamStartResult>;
  stopCameraStream(): Promise<void>;
  setCameraTorch(options: { enabled: boolean }): Promise<void>;
  startVideoRecording(options?: VideoRecordingOptions): Promise<{ started: boolean }>;
  stopVideoRecording(): Promise<VideoRecordingResult>;
  // Capacitor 3's WebPlugin returns a promise that is also a handle, and
  // removeAllListeners() returns a Promise (core/web-plugin.d.ts).
  addListener(event: 'gpsMockDetected', callback: (data: GPSVerificationResult) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'deviceIntegrityChanged', callback: (data: DeviceIntegrityResult) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'nativeErrorRetry', callback: () => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'locationPermissionChanged', callback: (data: { granted: boolean }) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'locationUpdate', callback: (data: LocationUpdate) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'cameraPermissionChanged', callback: (data: { granted: boolean }) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(event: 'cameraFrame', callback: (data: CameraFrame) => void): Promise<PluginListenerHandle> & PluginListenerHandle;
  removeAllListeners(): Promise<void>;
}

export const PluginSecure = 'PluginSecure';