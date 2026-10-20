import { WebPlugin } from '@capacitor/core';

import type {
  CallbackID,
  EnforceLocationOptions,
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

  async enforceLocation(options?: EnforceLocationOptions): Promise<LocationStatus> {
    // 1. Prompt permission immediately
    await this.requestPermissions();
    // 2. Check status
    const status = await this.checkLocationStatus();

    // 3. Render web gating blocker if unusable
    const existingOverlay = document.getElementById('wahana-web-gating-overlay');
    if (!status.usable) {
      if (!existingOverlay) {
        const overlay = document.createElement('div');
        overlay.id = 'wahana-web-gating-overlay';
        overlay.style.cssText =
          'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.9);z-index:999999;display:flex;align-items:center;justify-content:center;padding:20px;font-family:sans-serif;color:#fff;text-align:center;';

        const card = document.createElement('div');
        card.style.cssText =
          'background:#fff;color:#111;padding:24px;border-radius:12px;max-width:360px;width:100%;box-shadow:0 8px 24px rgba(0,0,0,0.3);';

        const title = document.createElement('h3');
        title.style.cssText = 'color:#d32f2f;margin-bottom:12px;font-size:1.2rem;';
        title.innerText = options?.title || 'Akses Lokasi Wajib Aktif';

        const msg = document.createElement('p');
        msg.style.cssText = 'font-size:0.9rem;color:#555;margin-bottom:20px;line-height:1.4;';
        msg.innerText =
          options?.messagePermissionDenied ||
          'Aplikasi tidak dapat digunakan tanpa izin akses lokasi yang aktif. Silakan izinkan akses lokasi di browser.';

        const btn = document.createElement('button');
        btn.style.cssText =
          'background:#0066cc;color:#fff;border:none;padding:10px 20px;border-radius:6px;font-weight:600;cursor:pointer;font-size:0.9rem;';
        btn.innerText = options?.actionButtonLabel || 'Coba Lagi';
        btn.onclick = () => {
          this.enforceLocation(options);
        };

        card.appendChild(title);
        card.appendChild(msg);
        card.appendChild(btn);
        overlay.appendChild(card);
        document.body.appendChild(overlay);
      }
    } else {
      if (existingOverlay) {
        existingOverlay.remove();
      }
    }

    this.notifyListeners('locationStatusChanged', status);
    return status;
  }

  async dismissGatingDialog(): Promise<void> {
    const existingOverlay = document.getElementById('wahana-web-gating-overlay');
    if (existingOverlay) {
      existingOverlay.remove();
    }
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
