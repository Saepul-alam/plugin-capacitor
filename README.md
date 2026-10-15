# @al/pluginsecure

Capacitor plugin providing native security features such as GPS mock-location detection, jailbreak/root detection, verified camera capture, barcode scanning, biometric authentication, and device‑integrity checks.

## Features
- Fake GPS Detection: detects mock locations, spoofing apps, and emulators (Android).
- Jailbreak/Root Detection: identifies Cydia, Magisk, substrate, sandbox escape (iOS/Android).
- Verified Camera: CameraX (Android) / AVFoundation (iOS) with GPS verification.
- EXIF Embedding: adds GPS coordinates, accuracy, timestamp, and verification tag to photo metadata.
- Configurable Thresholds: max GPS age, minimum accuracy, and requirement flags.
- Background Monitoring: periodic GPS integrity checks with event notifications.

## Installation
```bash
# Add the plugin to the project
npm i @al/pluginsecure
# Synchronise native projects
npx cap sync
```
### Android
The plugin adds the following Gradle dependencies automatically:
- `androidx.biometric:biometric:1.1.0`
- `com.google.mlkit:barcode-scanning:17.2.0`
After syncing, build the Android project:
```bash
cd android && ./gradlew assembleDebug   # or ./gradlew assembleRelease
```
Required Android permissions are inserted into `android/app/src/main/AndroidManifest.xml`:
- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.USE_BIOMETRIC`
- `android.permission.VIBRATE`

### iOS
The plugin defines a CocoaPods podspec. After syncing, install the pods:
```bash
cd ios && pod install
```
Xcode 11+ is required; the project links `LocalAuthentication` and `MLKitBarcodeScanning` frameworks.

## Registering the plugin
The plugin is already listed in `quasar.conf.js` under the `boot` array (`'native'`). The boot file `src/boot/native.js` creates a `$native` service:
```js
export default async ({ app }) => {
  app.config.globalProperties.$native = await NativePlugin.create();
};
```
You can access the API via `this.$native` (Options API) or `const { $native } = useQuasar();` (Composition API).

## Usage examples
```js
// Verify GPS location (detect mock locations & jailbreak)
async function verifyLocation() {
  const result = await this.$native.verifyGPS();
  console.log('GPS verification:', result);
  if (result.isMock) {
    // handle mock location – e.g., show warning or reject request
  }
}

// Capture a verified photo (requires successful GPS check)
async function capturePhoto() {
  const photo = await this.$native.captureVerifiedPhoto({
    maxWidth: 1024,
    maxHeight: 768,
    quality: 0.8,
    requireGPSVerification: true,
    maxGPSAgeMs: 5000,
    minAccuracyMeters: 20,
    embedEXIF: true,
    watermark: true,
  });
  console.log('Captured photo base64:', photo);
}

// Scan a barcode / QR code
async function scanCode() {
  const data = await this.$native.scanBarcode({
    formats: ['QR_CODE', 'CODE_128'],
  });
  console.log('Scanned barcode data:', data.text);
}

// Biometric authentication (fingerprint / face)
async function authBiometric() {
  const auth = await this.$native.authenticateBiometric({
    reason: 'Approve shipment',
  });
  if (auth.success) {
    console.log('Biometric success');
  } else {
    console.warn('Biometric failed:', auth.error);
  }
}

// Device integrity check (jailbreak / root detection)
async function checkIntegrity() {
  const integrity = await this.$native.checkDeviceIntegrity();
  console.log('Integrity result:', integrity);
}

// Continuous GPS monitoring
async function startMonitoring() {
  await this.$native.startGPSMonitoring({ intervalMs: 60000 });
}
async function stopMonitoring() {
  await this.$native.stopGPSMonitoring();
}
```
> **Note**: When running the app in a web browser (`quasar dev`), the `$native` service falls back to stub implementations that return safe defaults (e.g., `isMock: false`). This enables UI development without a device.

## Development tips
- Test native features on a device or emulator: `npx cap open android` or `npx cap open ios`.
- Source code resides under `web/dev/dcaf/src-capacitor/plugin_custom/pluginsecure/`.
- After modifying native code, repeat `npx cap sync` and rebuild the platform.
- Use Android Studio Logcat for Android debugging and Xcode console for iOS.

## License
MIT