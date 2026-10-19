import { WebPlugin } from '@capacitor/core';

import type {
  CallbackID,
  LocationStatus,
  PermissionStatus,
  Position,
  PositionOptions,
  WahanaPluginPlugin,
  WatchPositionCallback,
  WatchPositionOptions,
} from './definitions';

export class WahanaPluginWeb extends WebPlugin implements WahanaPluginPlugin {
  private watches: { [id: string]: number } = {};
  private nextWatchId = 1;

  async getCurrentPosition(options?: PositionOptions): Promise<Position> {
    return new Promise((resolve, reject) => {
      if (!('geolocation' in navigator)) {
        reject(new Error('Geolocation is not supported by this browser'));
        return;
      }

      navigator.geolocation.getCurrentPosition(
        (pos) => {
          resolve(this.formatWebPosition(pos));
        },
        (err) => {
          reject(err);
        },
        {
          enableHighAccuracy: options?.enableHighAccuracy ?? true,
          timeout: options?.timeout ?? 10000,
          maximumAge: options?.maximumAge ?? 0,
        },
      );
    });
  }

  async watchPosition(options: WatchPositionOptions, callback: WatchPositionCallback): Promise<CallbackID> {
    if (!('geolocation' in navigator)) {
      throw new Error('Geolocation is not supported by this browser');
    }

    const watchIdStr = (this.nextWatchId++).toString();
    const nativeWatchId = navigator.geolocation.watchPosition(
      (pos) => {
        const formatted = this.formatWebPosition(pos);
        callback(formatted);
        this.notifyListeners('location', formatted);
      },
      (err) => {
        callback(null, err);
      },
      {
        enableHighAccuracy: options?.enableHighAccuracy ?? true,
        timeout: options?.timeout ?? 10000,
        maximumAge: options?.maximumAge ?? 0,
      },
    );

    this.watches[watchIdStr] = nativeWatchId;
    return { id: watchIdStr };
  }

  async stopWatch(options: CallbackID): Promise<void> {
    if (this.watches[options.id] !== undefined) {
      navigator.geolocation.clearWatch(this.watches[options.id]);
      delete this.watches[options.id];
    }
  }

  async checkPermissions(): Promise<PermissionStatus> {
    if ('permissions' in navigator) {
      try {
        const status = await navigator.permissions.query({
          name: 'geolocation' as PermissionName,
        });
        const state = status.state as 'prompt' | 'granted' | 'denied';
        return {
          location: state,
          coarseLocation: state,
        };
      } catch {
        // Fallback if query not supported for geolocation
      }
    }
    return {
      location: 'prompt',
      coarseLocation: 'prompt',
    };
  }

  async requestPermissions(): Promise<PermissionStatus> {
    try {
      await this.getCurrentPosition({ timeout: 5000 });
      return {
        location: 'granted',
        coarseLocation: 'granted',
      };
    } catch {
      return this.checkPermissions();
    }
  }

  async isLocationEnabled(): Promise<{ enabled: boolean }> {
    const enabled = 'geolocation' in navigator;
    return { enabled };
  }

  async checkLocationStatus(): Promise<LocationStatus> {
    const isEnabled = 'geolocation' in navigator;
    const perm = await this.checkPermissions();
    const isGranted = perm.location === 'granted';
    const isMock = false;

    return {
      enabled: isEnabled,
      granted: isGranted,
      isMock,
      usable: isEnabled && isGranted && !isMock,
    };
  }

  async openLocationSettings(): Promise<void> {
    console.info('openLocationSettings is not supported on web.');
  }

  async openAppSettings(): Promise<void> {
    console.info('openAppSettings is not supported on web.');
  }

  private formatWebPosition(pos: GeolocationPosition): Position {
    return {
      timestamp: pos.timestamp,
      coords: {
        latitude: pos.coords.latitude,
        longitude: pos.coords.longitude,
        accuracy: pos.coords.accuracy,
        altitude: pos.coords.altitude,
        altitudeAccuracy: pos.coords.altitudeAccuracy,
        heading: pos.coords.heading,
        speed: pos.coords.speed,
        isMock: false,
      },
    };
  }
}
