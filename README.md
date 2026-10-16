# @al/pluginsecure

Enterprise Capacitor plugin providing mobile security (anti-spoofing, fake GPS, verified camera, biometrics, barcode scanning) and a seamless **Native App Experience** (native error screen interception, app state preservation across app switching, and native-feel tuning).

Supports **Capacitor 2** and **Capacitor 3** across Android and iOS.

---

## Features

### 🛡️ Native Error Interceptor (No Web/Chromium Error Pages)
- Intercepts HTTP $\ge 400$ (**404 Not Found**, 500, 502) and network disconnections (`ERR_INTERNET_DISCONNECTED`, `ERR_CONNECTION_REFUSED`, `ERR_TIMEOUT`).
- Replaces ugly Chromium/browser error pages (dinosaur, blank white screen) with a polished **Native Android Layout**.
- Includes native **"Coba Lagi" (Retry)** and **"Kembali" (Back)** action buttons.
- Supports programmatic invocation from Single Page Apps (Vue/Quasar): `PluginSecure.showNativeError(...)`.

### 🔄 App State Preservation & Anti-Reset
- Automatically preserves the active page/route when switching to other apps (multitasking/backgrounding).
- Prevents the app from resetting or reloading back to the initial splash/login screen when reopened from the launcher icon.
- Restores state across Android OS memory reclaim (*process death*).

### ⚡ Native Feel Tuning
- Disables web rubber-band overscroll glow (`OVER_SCROLL_NEVER`).
- Enables hardware acceleration and DOM storage caching to eliminate white flashes during page transitions.

### 🔒 Enterprise Mobile Security
- **Fake GPS & Mock Location Detection**: Detects mock providers, spoofing apps, and emulators.
- **Root & Jailbreak Detection**: Identifies Cydia, Magisk, Substrate, and tampering tools.
- **Verified Camera**: CameraX capture with tamper-proof GPS coordinates embedded into EXIF metadata.
- **Biometric Authentication**: Fingerprint and Face Unlock authentication.
- **Barcode & QR Scanner**: High performance barcode scanning powered by ML Kit.

---

## Installation

### From GitHub Repository
```bash
npm install @al/pluginsecure@github:Saepul-alam/plugin-capacitor#v1.0.0
# Or latest main branch:
npm install @al/pluginsecure@github:Saepul-alam/plugin-capacitor
```

### Sync with Capacitor
```bash
npx cap sync
```

### Android Task State Configuration
Run the idempotent helper script to ensure `alwaysRetainTaskState="true"` and `launchMode="singleTask"` in `AndroidManifest.xml`:
```bash
node node_modules/@al/pluginsecure/scripts/ensure-android.js android
```

---

## Usage in Quasar v1 / Vue 2 (or Quasar v2 / Vue 3)

### 1. Boot File Setup (`src/boot/pluginsecure.js`)
```javascript
import { setupAutoStatePreservation, PluginSecure } from '@al/pluginsecure';

export default ({ router, Vue }) => {
  // 1. Enable Native Feel (disable web overscroll, enable cache)
  PluginSecure.enableNativeFeel().catch(() => {});

  // 2. Setup state preservation across app switching (multitasking)
  setupAutoStatePreservation({
    router,
    onRestore: (savedState) => {
      if (savedState && savedState.lastRoute && savedState.lastRoute !== '/') {
        console.log('[PluginSecure] Restoring last route:', savedState.lastRoute);
        router.replace(savedState.lastRoute).catch(() => {});
      }
    }
  });

  // 3. Listen to retry events from native error screen
  PluginSecure.addListener('nativeErrorRetry', () => {
    if (router.currentRoute) {
      router.replace(router.currentRoute.fullPath).catch(() => {});
    }
  });

  if (Vue) {
    Vue.prototype.$pluginSecure = PluginSecure;
  }
};
```

Register the boot file in `quasar.conf.js`:
```javascript
boot: ['pluginsecure']
```

---

## API Reference

### Native Error Screen
```javascript
import { PluginSecure } from '@al/pluginsecure';

// Show native error overlay
await PluginSecure.showNativeError({
  title: 'Halaman Tidak Ditemukan',
  message: 'Rute yang Anda tuju tidak ditemukan pada sistem.',
  code: '404',
  canRetry: true
});

// Hide native error overlay
await PluginSecure.hideNativeError();
```

### State Preservation
```javascript
// Manually save state
await PluginSecure.saveAppState({
  url: window.location.href,
  route: '/orders/detail?id=123',
  data: { step: 2 }
});

// Get last saved state
const state = await PluginSecure.getLastAppState();
console.log('Last route:', state.lastRoute);
```

### Security & Camera
```javascript
// Verify GPS (anti-spoofing)
const gps = await PluginSecure.verifyGPS();
if (gps.isMock) {
  alert('Mock location detected!');
}

// Check Root / Jailbreak
const integrity = await PluginSecure.checkDeviceIntegrity();
if (integrity.isRooted) {
  alert('Device is rooted!');
}

// Biometric Auth
const bio = await PluginSecure.authenticateBiometric({
  title: 'Autentikasi Diperlukan',
  cancelButtonText: 'Batal'
});

// Barcode Scan
const scan = await PluginSecure.scanBarcode({ prompt: 'Arahkan kamera ke barcode' });
console.log('Barcode:', scan.content);
```

---

## License
MIT