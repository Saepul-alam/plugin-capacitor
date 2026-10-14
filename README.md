# @al/pluginsecure

Capacitor plugin for fake GPS detection and verified camera capture.

## Features
- 🔍 **Fake GPS Detection**: Mock location, spoofing apps, emulator detection (Android)
- 🔐 **Jailbreak/Root Detection**: Cydia, Magisk, substrate, sandbox escape (iOS/Android)
- 📸 **Verified Camera**: CameraX (Android) / AVFoundation (iOS) with GPS verification
- 📍 **EXIF Embedding**: GPS coords, accuracy, timestamp, verification tag in photo metadata
- 🎯 **Configurable Thresholds**: Max GPS age, min accuracy, require verification
- 📡 **Background Monitoring**: Periodic GPS integrity checks with events

## Install
```bash
npm install github:Saepul-alam/plugin-capacitor
npx cap sync
```

## Usage
```typescript
import { Plugins } from '@capacitor/core';
const { PluginSecure } = Plugins;

// Verify GPS
const gps = await PluginSecure.verifyGPS();
if (gps.isMock) { /* block action */ }

// Capture verified photo
const photo = await PluginSecure.captureVerifiedPhoto({
  quality: 90,
  direction: 'rear',
  requireGPSVerification: true,
  maxGPSAgeMs: 5000,
  minAccuracyMeters: 20,
  embedEXIF: true,
  watermark: true
});

// Listen for mock detection
await PluginSecure.addListener('gpsMockDetected', (data) => {
  console.warn('Mock GPS detected!', data);
});
```

## API
| Method | Description |
|--------|-------------|
| `verifyGPS()` | Check for mock location, spoofing apps, emulator |
| `checkDeviceIntegrity()` | Root/jailbreak detection |
| `captureVerifiedPhoto(options)` | Camera with GPS verification |
| `startGPSMonitoring({ intervalMs })` | Background monitoring |
| `stopGPSMonitoring()` | Stop monitoring |

## Requirements
- Capacitor 2+
- Android 21+ (CameraX), iOS 13+
- Camera & Location permissions

## License
MIT