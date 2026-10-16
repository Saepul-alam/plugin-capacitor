# @al/pluginsecure

Capacitor 3 plugin providing native security features for hybrid apps:

- **Fake GPS detection** — mock locations, spoofing apps, emulators (Android) and jailbreak hints (iOS).
- **Device integrity** — root / jailbreak detection.
- **Verified camera** — CameraX (Android) / AVFoundation (iOS) with GPS verification and EXIF embedding.
- **Barcode / QR scanning** — ML Kit (Android) / AVFoundation (iOS).
- **Biometric authentication** — fingerprint / face.
- **Native error UI & state preservation** — optional native overlay and restore-last-route helpers.

> This package targets **Capacitor 3** (`@capacitor/core >=3.0.0 <4.0.0`). It is verified against Quasar v1 (`@quasar/app` v2) Capacitor mode.

## Installation (Quasar v1 + Capacitor mode)

Create the Capacitor project, install the plugin, then run the one-shot setup:

```bash
npx quasar mode add capacitor
cd src-capacitor
npm i @al/pluginsecure
npx pluginsecure-setup
```

`npx pluginsecure-setup` performs the whole native integration:

1. **Upgrades `src-capacitor` to Capacitor 3.** `quasar mode add capacitor` still scaffolds Capacitor 2 (hard-coded in `@quasar/app`); the setup rewrites `src-capacitor/package.json` to `^3.9.0` and installs it.
2. Ensures `www/index.html`, then adds the `android` platform (and `ios` on macOS).
3. **Patches the Capacitor 3.9 Android template.** Its `cordovaAndroidVersion` pins `org.apache.cordova:framework:7.0.0`, which only ever lived on the now-defunct jcenter, so it also raises the dependency to a Maven Central release, bumps `minSdk` to 22 and adds `mavenCentral()`. Without this `gradlew assembleDebug` cannot resolve dependencies.
4. `cap sync` — writes `capacitor.plugins.json`, which Capacitor 3 uses to **auto-register** `@CapacitorPlugin` classes. No `MainActivity.java` edit is required.
5. `pod install` on macOS (skip with `PLUGINSECURE_SKIP_POD_INSTALL=1`; skip adding iOS with `PLUGINSECURE_SKIP_IOS=1`).
6. Generates `src/boot/pluginsecure.js` in the Quasar app root and adds `'pluginsecure'` to the `boot` array in `quasar.conf.js`.

`npm i @al/pluginsecure` also runs a lightweight `postinstall` that only generates the boot file, so `quasar dev` works immediately (disable with `PLUGINSECURE_SKIP_POSTINSTALL=1`). It does **not** spawn `npm`/`cap` itself: a nested install from a lifecycle script races with the running install, and the top-level npm rewrites `package.json` afterwards.

> Android builds require **JDK 11** — Capacitor 3.9's Gradle 7.0 / AGP 4.2.1 do not support JDK 17.

### Manual fallback

If scripts were disabled (`npm i --ignore-scripts`) or the project layout is non-standard, run the setup yourself from `src-capacitor`:

```bash
npm i @capacitor/core@^3 @capacitor/cli@^3 @capacitor/android@^3
node node_modules/@al/pluginsecure/scripts/setup.js   # upgrade + platforms + sync + boot file
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
| `scanBarcode(options)` | Returns `{ hasContent, content, format }`. |
| `checkBiometricAvailability()` | Returns `{ isAvailable, biometryType, reason }`. |
| `authenticateBiometric(options)` | Returns `{ success, error? }`. |
| `startGPSMonitoring(options)` / `stopGPSMonitoring()` | Periodic checks; emits `gpsMockDetected`. |
| `showNativeError(options)` / `hideNativeError()` | Native error overlay. |
| `saveAppState(options)` / `getLastAppState()` | Persist / read last route. |
| `enableStatePreservation(options)` / `restoreLastAppState()` / `enableNativeFeel()` | State + UX helpers. |
| `checkLocationPermission()` | Returns `{ granted, state, required }`. |
| `requestLocationPermission()` | Requests runtime location permission; returns `{ granted, state }`. |
| `isLocationRequired()` / `setLocationRequired({ enabled })` | Read / toggle the location requirement. |
| `openAppSettings()` / `openLocationSettings()` | Open the app / location settings screens. |

### Location permission gating

When `requireLocation` is enabled (default), `verifyGPS()`, `captureVerifiedPhoto()` and `startGPSMonitoring()` first ensure the runtime location permission is granted: the plugin prompts the OS, then resumes the original call. Emits `locationPermissionChanged` with `{ granted }`. If the user denies, calls reject with code `LOCATION_PERMISSION_DENIED`.

Configure the default via `capacitor.config.json`:

```json
{ "plugins": { "PluginSecure": { "requireLocation": true } } }
```

The generated `src/boot/pluginsecure.js` reads a `REQUIRE_LOCATION` constant (default `true`) and blocks the whole app with a full-screen gate when access is denied, offering buttons to the app / location settings. Set `REQUIRE_LOCATION = false` to disable the gate in that build; runtime state (`setLocationRequired`) overrides the config default.


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
