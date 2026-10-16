# @al/pluginsecure

Capacitor 2 plugin providing native security features for hybrid apps:

- **Fake GPS detection** — mock locations, spoofing apps, emulators (Android) and jailbreak hints (iOS).
- **Device integrity** — root / jailbreak detection.
- **Verified camera** — CameraX (Android) / AVFoundation (iOS) with GPS verification and EXIF embedding.
- **Barcode / QR scanning** — ML Kit (Android) / AVFoundation (iOS).
- **Biometric authentication** — fingerprint / face.
- **Native error UI & state preservation** — optional native overlay and restore-last-route helpers.

> This package targets **Capacitor 2** (`@capacitor/core >=2.5.0 <3.0.0`). It is verified against Quasar v1 (`@quasar/app` v2) Capacitor mode.

## Installation (Quasar v1 + Capacitor mode)

Capacitor plugins must be installed inside the Capacitor project, which Quasar creates at `src-capacitor`:

```bash
cd src-capacitor
npm i @al/pluginsecure
```

The `postinstall` hook then performs the whole native integration automatically:

1. `cap sync` — wires the plugin into `capacitor.settings.gradle` / Xcode pods.
2. `ensure-android.js` — registers `PluginSecurePlugin` in `MainActivity.java` (required by Capacitor 2).
3. `pod install` on macOS (skip with `PLUGINSECURE_SKIP_POD_INSTALL=1`).
4. Generates `src/boot/pluginsecure.js` in the Quasar app root and adds `'pluginsecure'` to the `boot` array in `quasar.conf.js`.

Disable the whole hook with `PLUGINSECURE_SKIP_POSTINSTALL=1`.

### Manual fallback

If scripts were disabled (`npm i --ignore-scripts`) or the project layout is non-standard, run the steps yourself from `src-capacitor`:

```bash
npx cap sync android
node node_modules/@al/pluginsecure/scripts/ensure-android.js android
node node_modules/@al/pluginsecure/scripts/auto-install.js   # re-runs cap sync + ensure-android + boot file
```

Then build the native app:

```bash
quasar build -m capacitor -T android
# or open the platform
npx cap open android
npx cap open ios
```

## Usage

A ready-to-use service is injected as `$pluginsecure` (Options API) after the auto-generated boot file is registered:

```js
async function verifyLocation () {
  const result = await this.$pluginsecure.verifyGPS()
  if (result.isMock) {
    // handle mock location
  }
}
```

You can also access the native plugin directly:

```js
import { Capacitor } from '@capacitor/core'

const plugin = Capacitor.Plugins.PluginSecure
const photo = await plugin.captureVerifiedPhoto({ requireGPSVerification: true })
```

### API

| Method | Description |
| --- | --- |
| `verifyGPS()` | Returns `{ isMock, isJailbroken, details }`. |
| `checkDeviceIntegrity()` | Returns `{ isRooted, isJailbroken, details }`. |
| `captureVerifiedPhoto(options)` | Verified capture; returns `{ uri, base64, dataUrl, exif }`. |
| `scanBarcode(options)` | Returns `{ hasContent, content, format }`. |
| `checkBiometricAvailability()` | Returns `{ isAvailable, biometryType, reason }`. |
| `authenticateBiometric(options)` | Returns `{ success, error? }`. |
| `startGPSMonitoring(options)` / `stopGPSMonitoring()` | Periodic checks; emits `gpsMockDetected`. |
| `showNativeError(options)` / `hideNativeError()` | Native error overlay. |
| `saveAppState(options)` / `getLastAppState()` | Persist / read last route. |
| `enableStatePreservation(options)` / `restoreLastAppState()` / `enableNativeFeel()` | State + UX helpers. |

### Web / browser (`quasar dev`)

The generated `src/boot/pluginsecure.js` provides browser fallbacks: `verifyGPS` uses the browser geolocation API, `captureVerifiedPhoto` opens a file input, `scanBarcode` prompts for manual input. This lets you develop the UI without a device.

## Android permissions

The plugin manifest contributes the following (merged automatically by `cap sync`):

- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.CAMERA`
- `android.permission.USE_BIOMETRIC`
- `android.permission.VIBRATE`

## iOS

The podspec `AlPluginsecure.podspec` is consumed by CocoaPods after `cap sync`. Requires Xcode 11+ and CocoaPods.

## Development

```bash
npm install      # installs deps + runs build + postinstall
npm run build    # dist/esm + dist/cjs
npm run watch    # incremental build
```

Source layout: `src/index.ts` (web bridge), `src/definitions.ts` (types), `src/android`, `src/ios`.

## License

MIT
