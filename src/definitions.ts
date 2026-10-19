import type { PluginListenerHandle } from '@capacitor/core';

export type PermissionState = 'prompt' | 'prompt-with-rationale' | 'granted' | 'denied';

export interface LocationCoordinates {
  /**
   * Latitude in degrees.
   */
  latitude: number;
  /**
   * Longitude in degrees.
   */
  longitude: number;
  /**
   * Accuracy level of the coordinate in meters.
   */
  accuracy: number;
  /**
   * Altitude in meters above the WGS 84 reference ellipsoid, or null if unavailable.
   */
  altitude: number | null;
  /**
   * Accuracy of altitude in meters, or null if unavailable.
   */
  altitudeAccuracy: number | null;
  /**
   * Direction of travel in degrees (0 - 359.99), or null if unavailable.
   */
  heading: number | null;
  /**
   * Current velocity in meters per second, or null if unavailable.
   */
  speed: number | null;
  /**
   * True if location is detected to be simulated / fake (Mock Location / Fake GPS).
   */
  isMock: boolean;
}

export interface Position {
  /**
   * Timestamp in milliseconds when the position was retrieved.
   */
  timestamp: number;
  /**
   * Geographic coordinates and metadata.
   */
  coords: LocationCoordinates;
}

export interface PositionOptions {
  /**
   * If true, device requests the most accurate location available (GPS). Default: true.
   */
  enableHighAccuracy?: boolean;
  /**
   * Maximum time in milliseconds allowed to return position. Default: 10000ms.
   */
  timeout?: number;
  /**
   * Maximum age of a cached location in milliseconds that is acceptable. Default: 0.
   */
  maximumAge?: number;
}

export interface WatchPositionOptions extends PositionOptions {
  /**
   * Desired update interval in milliseconds (Android). Default: 3000ms.
   */
  interval?: number;
  /**
   * Fastest update interval in milliseconds (Android). Default: 1000ms.
   */
  fastestInterval?: number;
  /**
   * Minimum distance displacement in meters before updating. Default: 0.
   */
  minimumDistance?: number;
}

export interface CallbackID {
  /**
   * Identifier returned by watchPosition, used to stop watching.
   */
  id: string;
}

export type WatchPositionCallback = (position: Position | null, err?: any) => void;

export interface PermissionStatus {
  /**
   * Status of fine location permission.
   */
  location: PermissionState;
  /**
   * Status of coarse location permission.
   */
  coarseLocation?: PermissionState;
}

export interface LocationStatus {
  /**
   * True if system location services (GPS) are enabled on the device.
   */
  enabled: boolean;
  /**
   * True if app has been granted location permissions.
   */
  granted: boolean;
  /**
   * True if a mock location provider (Fake GPS) is currently active.
   */
  isMock: boolean;
  /**
   * True if location is fully usable (enabled && granted && !isMock).
   * Used for application gating.
   */
  usable: boolean;
}

export interface WahanaPluginPlugin {
  /**
   * Get current device position once with anti-mock verification.
   *
   * @since 0.0.1
   */
  getCurrentPosition(options?: PositionOptions): Promise<Position>;

  /**
   * Start watching device position in real-time.
   *
   * @since 0.0.1
   */
  watchPosition(options: WatchPositionOptions, callback: WatchPositionCallback): Promise<CallbackID>;

  /**
   * Stop watching device position for the given watch ID.
   *
   * @since 0.0.1
   */
  stopWatch(options: CallbackID): Promise<void>;

  /**
   * Check current location permission status.
   *
   * @since 0.0.1
   */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Request location permissions from the user.
   *
   * @since 0.0.1
   */
  requestPermissions(permissions?: { permissions: ('location' | 'coarseLocation')[] }): Promise<PermissionStatus>;

  /**
   * Check whether system location services (GPS) are enabled.
   *
   * @since 0.0.1
   */
  isLocationEnabled(): Promise<{ enabled: boolean }>;

  /**
   * Check combined location status for gating (enabled, granted, isMock, usable).
   *
   * @since 0.0.1
   */
  checkLocationStatus(): Promise<LocationStatus>;

  /**
   * Open the system location / GPS settings page.
   *
   * @since 0.0.1
   */
  openLocationSettings(): Promise<void>;

  /**
   * Open the application's details / permission settings page.
   *
   * @since 0.0.1
   */
  openAppSettings(): Promise<void>;

  /**
   * Listen for real-time changes in location status (GPS on/off, permission, fake GPS).
   *
   * @since 0.0.1
   */
  addListener(
    eventName: 'locationStatusChanged',
    listenerFunc: (status: LocationStatus) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Listen for streaming position updates.
   *
   * @since 0.0.1
   */
  addListener(eventName: 'location', listenerFunc: (position: Position) => void): Promise<PluginListenerHandle>;
}
