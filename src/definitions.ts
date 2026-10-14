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

export interface PluginSecurePlugin {
  verifyGPS(): Promise<GPSVerificationResult>;
  startGPSMonitoring(options?: { intervalMs?: number }): Promise<void>;
  stopGPSMonitoring(): Promise<void>;
  captureVerifiedPhoto(options: VerifiedPhotoOptions): Promise<VerifiedPhotoResult>;
  checkDeviceIntegrity(): Promise<DeviceIntegrityResult>;
  addListener(event: 'gpsMockDetected', callback: (data: GPSVerificationResult) => void): Promise<PluginListenerHandle>;
  addListener(event: 'deviceIntegrityChanged', callback: (data: DeviceIntegrityResult) => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export const PluginSecure = 'PluginSecure';