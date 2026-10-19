# @al/pluginsecure

Capacitor 3 plugin providing native security features for hybrid (Quasar v1) apps:

- **Fake GPS detection** — mock locations, spoofing apps, emulators (Android) and jailbreak hints (iOS).
- **Verified camera** — CameraX (Android) / AVFoundation (iOS) with GPS verification and EXIF embedding.
- **Location & camera permission gating** — optionally requires location access (full-screen gate) and guides the user to grant camera access when a camera feature is used.
- **Transparent camera integration (auto-on)** — the generated boot file routes `@capacitor/camera`'s `Camera.getPhoto()` to the native verified camera and polyfills `navigator.mediaDevices.getUserMedia()` with a native camera stream, so existing face/preview code keeps working unmodified. Also exposes native **burst capture**, **watermark** and **video recording** APIs. All individually disableable.
- **Native error UI** — native overlay for 404/5xx, connection and SSL errors.

> Targets **Capacitor 3** (`@capacitor/core >=3.0.0 <4.0.0`). Verified against Quasar v1 (`@quasar/app` v2) Capacitor mode.

> Bahasa Indonesia: [README.id.md](./README.id.md)

## Requirements

- Node.js 14+ and npm 6+.
- **JDK 11** for Android builds — Capacitor 3.9's Gradle 7.0 / AGP 4.2.1 do not support JDK 17.
- Android SDK (`ANDROID_HOME`) with build-tools 30.0.3 and **`compileSdkVersion 31`** — the CameraX 1.1.x artifacts (camera-video) declare `minCompileSdk=31`.
- iOS: macOS, Xcode 11+, CocoaPods.

## Installation (Quasar v1 + Capacitor mode)

```bash
npx quasar mode add capacitor
cd src-capacitor
npm i @al/pluginsecure
npx pluginsecure-setup
```

`npx pluginsecure-setup` performs the whole native integration:

1. **Upgrades `src-capacitor` to Capacitor 3.** `quasar mode add capacitor` still scaffolds Capacitor 2 (hard-coded in `@quasar/app`); the setup rewrites `src-capacitor/package.json` to `^3.9.0` and installs it.
2. Ensures `www/index.html`, then adds the `android` platform (and `ios` on macOS).
3. **Patches the Capacitor 3.9 Android template.** Its `cordovaAndroidVersion` pins `org.apache.cordova:framework:7.0.0`, which only lived on the now-defunct jcenter; the setup raises it to a Maven Central release, bumps `minSdk` to 22 and adds `mavenCentral()`.
4. `cap sync` — writes `capacitor.plugins.json`, which Capacitor 3 uses to **auto-register** `@CapacitorPlugin` classes. No `MainActivity.java` edit is required.
5. `pod install` on macOS (skip with `PLUGINSECURE_SKIP_POD_INSTALL=1`; skip adding iOS entirely with `PLUGINSECURE_SKIP_IOS=1`).
6. Generates `src/boot/pluginsecure.js` in the Quasar app root and adds `'pluginsecure'` to the `boot` array in `quasar.conf.js`.

`npm i @al/pluginsecure` also runs a lightweight `postinstall` that only generates the boot file, so `quasar dev` works immediately (disable with `PLUGINSECURE_SKIP_POSTINSTALL=1`). It does **not** spawn `npm`/`cap` itself: a nested install from a lifecycle script races with the running install, and the top-level npm rewrites `package.json` afterwards.

### Environment flags

| Flag | Effect |
| --- | --- |
| `PLUGINSECURE_SKIP_POSTINSTALL=1` | Skip the automatic boot-file generation. |
| `PLUGINSECURE_SKIP_IOS=1` | Do not add/configure the iOS platform. |
| `PLUGINSECURE_SKIP_POD_INSTALL=1` | Skip `pod install`. |
| `PLUGINSECURE_NESTED_INSTALL=1` | Mark a nested setup run (set automatically). |

### Manual fallback

If scripts were disabled (`npm i --ignore-scripts`) or the layout is non-standard:

```bash
npm i @capacitor/core@^3 @capacitor/cli@^3 @capacitor/android@^3
node node_modules/@al/pluginsecure/scripts/setup.js   # upgrade + platforms + sync + boot file
```

Then build the native app:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)          # macOS, JDK 11
export NODE_OPTIONS=--openssl-legacy-provider             # Node 17+ with webpack 4
quasar build -m capacitor -T android --debug
# or open the platform
npx cap open android
npx cap open ios
```

## Configuration

All options live under `plugins.PluginSecure` in `src-capacitor/capacitor.config.json`:

```json
{
  "plugins": {
    "PluginSecure": {
      "requireLocation": true
    }
  }
}
```

| Key | Type | Default | Effect |
| --- | --- | --- | --- |
| `requireLocation` | boolean | `true` | Make the app require runtime location permission. When denied, the generated boot file shows a full-screen gate with shortcuts to the app/location settings. |
| `locationWatch` | boolean | `true` | Bridge `navigator.geolocation.getCurrentPosition()` / `watchPosition()` to the native realtime location stream (with mock detection). Existing pages get realtime location with no code changes. |
| `cameraOverride` | boolean | `true` | Route `Camera.getPhoto()` (from `@capacitor/camera`) to the native verified camera. Fail-safe: on any error the original browser/native call runs. |
| `cameraStream` | boolean | `true` | Polyfill `navigator.mediaDevices.getUserMedia({ video: true })` with a native camera stream (frames → `canvas.captureStream()`). Audio is not bridged. Falls back to the browser implementation on failure. |
| `captureBurst` | boolean | `true` | Enable the native `captureBurst()` API (multi-frame capture for liveness/perspective flows). |
| `videoRecorder` | boolean | `true` | Enable the native `startVideoRecording()` / `stopVideoRecording()` API. |
| `watermark` | boolean | `true` | Burn GPS + timestamp onto captured photos by default. |

Flags can be overridden at runtime (persisted on the device):

```js
await this.$pluginsecure.setLocationRequired({ enabled: false })
await this.$pluginsecure.setCameraFeature({ feature: 'cameraOverride', enabled: false })
await this.$pluginsecure.setLocationFeature({ feature: 'locationWatch', enabled: false })
```

> Changing `capacitor.config.json` requires `npx cap sync` (or re-running `pluginsecure-setup`).

### Camera features (default on)

The generated boot file installs two transparent integrations the moment the native plugin is present:

1. **`Camera.getPhoto()` override.** Any call to `@capacitor/camera`'s `Camera.getPhoto()` is routed to the native verified camera and converted back to the Capacitor `Photo` shape (`base64String`, `dataUrl`, `path`, `webPath`, `format`, `exif`). If anything goes wrong the original call runs unchanged, so no feature ever hard-breaks.
2. **`getUserMedia()` polyfill.** `navigator.mediaDevices.getUserMedia({ video: true })` is served from a native camera stream: the native side pushes JPEG frames via the `cameraFrame` event and the boot file paints them into a `<canvas>` exposed through `canvas.captureStream()`. Existing live-preview, Quagga / barcode and `@vladmandic/face-api` code keeps working with **no app changes**. `applyConstraints({ advanced: [{ torch }] })` and `facingMode` switching are emulated. Frames are delivered **upright**: `startCameraStream` reports the device `rotation`, each `cameraFrame` carries it, and the polyfill sizes the canvas to the natural (swapped for 90/270) orientation. The track's `getCapabilities().torch` reflects the active camera's real flash unit. Video-only: `audio` constraints fall back to the browser implementation.

Extra native APIs (opt-in, all default-on via flags):

```js
// Multi-frame capture for liveness / multi-pose flows (native).
const { frames } = await this.$pluginsecure.captureBurst({ count: 5, intervalMs: 250, direction: 'front' })

// Full-screen native video recorder (CameraX VideoCapture / AVFoundation).
const { uri, path, durationMs } = await this.$pluginsecure.startVideoRecording({ quality: 'high' })

// Direct camera-stream control (usually handled by the getUserMedia polyfill).
await this.$pluginsecure.startCameraStream({ facingMode: 'environment', width: 640, height: 480 })
await this.$pluginsecure.setCameraTorch({ enabled: true })
await this.$pluginsecure.stopCameraStream()
```

> **Android:** the video recorder uses CameraX 1.1.x, so the host app must set `compileSdkVersion 31` (or newer). Without it Gradle fails `checkDebugAarMetadata`. iOS video recording is not yet implemented (the methods reject).

### Realtime location (default on)

While the app is open or multitasking (minimized but not closed), the plugin streams the device location from the native source (FusedLocationProvider / CoreLocation) and flags mock providers on each fix.

```js
// One-off fix.
const loc = await this.$pluginsecure.getCurrentLocation()
console.log(loc.latitude, loc.longitude, loc.accuracy, loc.isMock)

// Continuous updates (emits `locationUpdate`).
await this.$pluginsecure.startLocationUpdates({ intervalMs: 2000, distanceFilter: 0, highAccuracy: true })

import { registerPlugin } from '@capacitor/core'
const plugin = registerPlugin('PluginSecure')
const handle = await plugin.addListener('locationUpdate', (loc) => {
  // loc: { latitude, longitude, altitude, accuracy, speed, heading, timestamp, provider, ageMs, isMock }
})
// later: handle.remove(); await this.$pluginsecure.stopLocationUpdates()
```

- **Foreground service (Android).** Starting updates launches a foreground service with an ongoing notification (`Melacak lokasi`); this keeps the process alive while the app is multitasking. Updates stop automatically on `stopLocationUpdates()` and when the WASM/WebView is destroyed. No `ACCESS_BACKGROUND_LOCATION` ("Allow all the time") is required.
- **iOS.** Set `UIBackgroundModes: [location]` in the app's `Info.plist` to keep updates alive in the background; the icon indicator (blue arrow) will be shown.
- **`locationWatch` polyfill.** With the flag on (default) the boot file rewrites `navigator.geolocation.getCurrentPosition()` / `watchPosition()` to the native stream, so existing code (e.g. Leaflet/geolocation helpers) gets realtime updates + mock detection without changes.

## Usage

After the boot file is registered, a service is injected as `$pluginsecure` (Options API):

```js
async function verifyLocation () {
  const result = await this.$pluginsecure.verifyGPS()
  if (result.isMock) {
    // handle mock location
  }
}
```

Or use the native plugin directly:

```js
import { registerPlugin } from '@capacitor/core'

const plugin = registerPlugin('PluginSecure')
const photo = await plugin.captureVerifiedPhoto({ requireGPSVerification: true })
```

### API

| Method | Description |
| --- | --- |
| `verifyGPS()` | Returns `{ isMock, isJailbroken, details }`. |
| `checkDeviceIntegrity()` | Returns `{ isRooted, isJailbroken, details }`. |
| `captureVerifiedPhoto(options)` | Verified capture; returns `{ uri, base64, dataUrl, exif }`. |
| `startGPSMonitoring(options)` / `stopGPSMonitoring()` | Periodic checks; emits `gpsMockDetected`. |
| `showNativeError(options)` / `hideNativeError()` | Native error overlay. |
| `checkLocationPermission()` | Returns `{ granted, state, required }`. |
| `requestLocationPermission()` | Requests runtime location permission; returns `{ granted, state }`. |
| `isLocationRequired()` / `setLocationRequired({ enabled })` | Read / toggle the location requirement. |
| `getCurrentLocation()` | One-off native location fix; returns a `LocationUpdate`. |
| `startLocationUpdates(options)` / `stopLocationUpdates()` | Continuous native location; emits `locationUpdate`. Options: `intervalMs` (2000), `distanceFilter` (0), `highAccuracy` (true). |
| `getLocationConfig()` | Returns `{ locationWatch }`. |
| `setLocationFeature({ feature, enabled })` | Toggle `locationWatch`; returns the updated config. |
| `checkCameraPermission()` / `requestCameraPermission()` | Check / request the runtime camera permission. |
| `openAppSettings()` / `openLocationSettings()` | Open the app / location settings screens. |
| `getCameraConfig()` | Returns the five camera feature flags. |
| `setCameraFeature({ feature, enabled })` | Toggle one camera flag; returns the updated config. |
| `captureBurst(options)` | Native multi-frame capture; returns `{ frames, exif }`. |
| `startCameraStream(options)` / `stopCameraStream()` | Native headless camera stream emitting `cameraFrame`. |
| `setCameraTorch({ enabled })` | Torch control for the active stream. |
| `startVideoRecording(options)` / `stopVideoRecording()` | Native video recorder; returns `{ uri, path, durationMs, hasAudio }`. |

### Events

| Event | Payload |
| --- | --- |
| `gpsMockDetected` | `GPSVerificationResult` |
| `deviceIntegrityChanged` | `DeviceIntegrityResult` |
| `nativeErrorRetry` | — |
| `locationPermissionChanged` | `{ granted: boolean }` |
| `locationUpdate` | `{ latitude: number, longitude: number, altitude: number \| null, accuracy: number, speed: number \| null, heading: number \| null, timestamp: number, provider: string, ageMs: number, isMock: boolean }` |
| `cameraPermissionChanged` | `{ granted: boolean }` |
| `cameraFrame` | `{ base64: string, timestamp: number, rotation?: number, frameWidth?: number, frameHeight?: number, facingMode?: string, torch?: boolean }` |

### Location permission gating

When `requireLocation` is enabled (default), `verifyGPS()`, `captureVerifiedPhoto()`, `startGPSMonitoring()`, `getCurrentLocation()` and `startLocationUpdates()` first ensure the runtime location permission is granted: the plugin prompts the OS, then resumes the original call. If the user denies, the call rejects with code `LOCATION_PERMISSION_DENIED`.

The generated `src/boot/pluginsecure.js` installs a full-screen, non-dismissible gate whenever `isLocationRequired()` is true and permission is not granted, offering **Pengaturan Lokasi**, **Buka Pengaturan Aplikasi** and **Periksa Lagi**. To disable it, set `requireLocation: false` in `capacitor.config.json` and re-run `cap sync` — no code change needed.

```js
import { registerPlugin } from '@capacitor/core'
const plugin = registerPlugin('PluginSecure')

const { granted } = await plugin.checkLocationPermission()
if (!granted) {
  const res = await plugin.requestLocationPermission()
  if (!res.granted) {
    await plugin.openAppSettings()
  }
}
```

### Camera permission gating

The camera is only needed when a camera feature is actually used. On boot the generated `src/boot/pluginsecure.js` checks the permission and, when it is missing, shows a **non-blocking banner** ("Izin kamera belum aktif"). When a camera action runs (`getUserMedia` / `Camera.getPhoto`), `CameraGate` ensures the permission: it prompts the OS while the status is `prompt`, and shows a full-screen overlay with **Buka Pengaturan Aplikasi**, **Minta Izin Lagi** and **Tutup** when the permission is denied. The banner and overlay clear automatically once the permission is granted.

Natively, `startCameraStream()` also requests the permission and resumes the call (`cameraPermissionChanged` is emitted); if denied it rejects with `CAMERA_PERMISSION_DENIED`.

```js
const { granted } = await plugin.checkCameraPermission()
if (!granted) {
  const res = await plugin.requestCameraPermission()
  if (!res.granted) await plugin.openAppSettings()
}
```

### Web / browser (`quasar dev`)

The generated `src/boot/pluginsecure.js` provides browser fallbacks: `verifyGPS`, `getCurrentLocation` and `startLocationUpdates` use the browser geolocation API and `captureVerifiedPhoto` opens a file input (no mock detection on web — `isMock` is always `false`). The location and camera gates are disabled on web. This lets you develop the UI without a device.

## Android permissions

The plugin manifest contributes the following (merged automatically by `cap sync`):

- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.ACCESS_BACKGROUND_LOCATION` (only needed if you also track without the foreground service)
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_LOCATION`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO` (native video recording)

## iOS

The podspec `AlPluginsecure.podspec` is consumed by CocoaPods after `cap sync`. Requires Xcode 11+ and CocoaPods. Add to `Info.plist` as needed:

- `NSLocationWhenInUseUsageDescription` (location gating / GPS verification)
- `NSLocationAlwaysAndWhenInUseUsageDescription` (realtime updates while backgrounded)
- `UIBackgroundModes` → `location` (realtime updates while backgrounded; shows the location indicator)
- `NSCameraUsageDescription` (verified camera)

## Troubleshooting

- **`createHash('md4')` / `digital envelope routines::unsupported`** — Node 17+ with webpack 4. Set `NODE_OPTIONS=--openssl-legacy-provider`.
- **Gradle/AGP fails on JDK 17** — build with JDK 11: `export JAVA_HOME=$(/usr/libexec/java_home -v 11)`.
- **`Could not find org.apache.cordova:framework:7.0.0`** — re-run `npx pluginsecure-setup` so the Android template is patched.
- **Boot file missing after `npm i --ignore-scripts`** — run `node node_modules/@al/pluginsecure/scripts/setup.js`.
- **App shows the last page on launch** — that is the WebView being kept alive while the process was in the background; after a full close or OS kill it always starts fresh.
- **Location stops while multitasking (Xiaomi/MIUI)** — allow the app to "Autostart" and set battery usage to "No restrictions" in the device settings; a foreground service (with the ongoing notification) is required and is started automatically by `startLocationUpdates()`.

## Development

```bash
npm install      # installs deps + runs build + postinstall
npm run build    # dist/esm + dist/cjs
npm run watch    # incremental build
```

Source layout: `src/index.ts` (web bridge), `src/definitions.ts` (types), `src/web.ts`, `src/android`, `src/ios`.

## License

MIT
