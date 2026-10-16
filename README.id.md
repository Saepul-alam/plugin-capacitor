# @al/pluginsecure

Plugin Capacitor 3 yang menyediakan fitur keamanan native untuk aplikasi hybrid (Quasar v1):

- **Deteksi Fake GPS** — mock location, aplikasi spoofing, emulator (Android) dan indikasi jailbreak (iOS).
- **Kamera terverifikasi** — CameraX (Android) / AVFoundation (iOS) dengan verifikasi GPS dan penanaman EXIF.
- **Scan Barcode / QR** — ML Kit (Android) / AVFoundation (iOS).
- **Autentikasi biometrik** — sidik jari / wajah.
- **Gate izin lokasi** — opsional mewajibkan akses lokasi dan memblokir app dengan pintasan ke pengaturan saat izin ditolak.
- **Native error UI & state preservation** — overlay native dan pemulihan rute terakhir yang *close-aware*.

> Menargetkan **Capacitor 3** (`@capacitor/core >=3.0.0 <4.0.0`). Terverifikasi pada Quasar v1 (`@quasar/app` v2) mode Capacitor.

> English: [README.md](./README.md)

## Kebutuhan

- Node.js 14+ dan npm 6+.
- **JDK 11** untuk build Android — Gradle 7.0 / AGP 4.2.1 di Capacitor 3.9 tidak mendukung JDK 17.
- Android SDK (`ANDROID_HOME`) dengan build-tools 30.0.3.
- iOS: macOS, Xcode 11+, CocoaPods.

## Pemasangan (Quasar v1 + mode Capacitor)

```bash
npx quasar mode add capacitor
cd src-capacitor
npm i @al/pluginsecure
npx pluginsecure-setup
```

`npx pluginsecure-setup` melakukan seluruh integrasi native:

1. **Upgrade `src-capacitor` ke Capacitor 3.** `quasar mode add capacitor` masih membuat Capacitor 2 (hard-code di `@quasar/app`); setup menulis ulang `src-capacitor/package.json` ke `^3.9.0` lalu menginstalnya.
2. Memastikan `www/index.html`, lalu menambahkan platform `android` (dan `ios` di macOS).
3. **Menambal template Android Capacitor 3.9.** `cordovaAndroidVersion`-nya mematok `org.apache.cordova:framework:7.0.0` yang hanya ada di jcenter (sudah mati); setup menaikkannya ke rilis Maven Central, menaikkan `minSdk` ke 22 dan menambahkan `mavenCentral()`.
4. `cap sync` — menulis `capacitor.plugins.json`, yang dipakai Capacitor 3 untuk **auto-register** kelas `@CapacitorPlugin`. Tidak perlu mengedit `MainActivity.java`.
5. `pod install` di macOS (lewati dengan `PLUGINSECURE_SKIP_POD_INSTALL=1`; lewati penambahan iOS dengan `PLUGINSECURE_SKIP_IOS=1`).
6. Mem-generate `src/boot/pluginsecure.js` di root app Quasar dan menambahkan `'pluginsecure'` ke array `boot` di `quasar.conf.js`.

`npm i @al/pluginsecure` juga menjalankan `postinstall` ringan yang hanya mem-generate boot file, sehingga `quasar dev` langsung jalan (matikan dengan `PLUGINSECURE_SKIP_POSTINSTALL=1`). Script ini **tidak** menjalankan `npm`/`cap` sendiri: install bersarang dari lifecycle script akan beradu dengan install yang sedang berjalan, dan npm level atas menulis ulang `package.json` setelahnya.

### Flag environment

| Flag | Efek |
| --- | --- |
| `PLUGINSECURE_SKIP_POSTINSTALL=1` | Lewati pembuatan boot file otomatis. |
| `PLUGINSECURE_SKIP_IOS=1` | Jangan tambah/konfigurasi platform iOS. |
| `PLUGINSECURE_SKIP_POD_INSTALL=1` | Lewati `pod install`. |
| `PLUGINSECURE_NESTED_INSTALL=1` | Tandai setup bersarang (otomatis). |

### Cara manual (fallback)

Kalau script dinonaktifkan (`npm i --ignore-scripts`) atau struktur project tidak standar:

```bash
npm i @capacitor/core@^3 @capacitor/cli@^3 @capacitor/android@^3
node node_modules/@al/pluginsecure/scripts/setup.js   # upgrade + platform + sync + boot file
```

Lalu build app native:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)          # macOS, JDK 11
export NODE_OPTIONS=--openssl-legacy-provider             # Node 17+ dengan webpack 4
quasar build -m capacitor -T android --debug
# atau buka platform
npx cap open android
npx cap open ios
```

## Konfigurasi

Semua opsi berada di bawah `plugins.PluginSecure` pada `src-capacitor/capacitor.config.json`:

```json
{
  "plugins": {
    "PluginSecure": {
      "requireLocation": true,
      "statePreservation": true
    }
  }
}
```

| Key | Tipe | Default | Efek |
| --- | --- | --- | --- |
| `requireLocation` | boolean | `true` | Mewajibkan izin lokasi runtime. Bila ditolak, boot file yang di-generate menampilkan gate layar penuh dengan pintasan ke pengaturan app/lokasi. |
| `statePreservation` | boolean | `true` | Memulihkan rute terakhir saat cold start. **Close-aware**: rute terakhir hanya dipulihkan bila app dibunuh OS di background; bila user sendiri menutup app, app mulai dari awal. Set `false` untuk selalu mulai dari awal pada setiap cold start. |

Keduanya bisa ditimpa saat runtime (tersimpan di perangkat):

```js
await this.$pluginsecure.setLocationRequired({ enabled: false })
await this.$pluginsecure.enableStatePreservation({ enabled: false })
```

> Mengubah `capacitor.config.json` perlu `npx cap sync` (atau jalankan ulang `pluginsecure-setup`).

### Perilaku state preservation

| Situasi | Hasil |
| --- | --- |
| Pindah ke app lain / multitasking (app tidak ditutup) | **Tetap di halaman yang sama** (WebView dibiarkan hidup). |
| User menutup app (swipe dari Recents, atau back-exit) | **Fresh** — mulai dari halaman awal saat dibuka lagi. |
| OS membunuh app di background (memori rendah) | **Resume** halaman terakhir. |
| `statePreservation: false` | **Fresh** di setiap cold start. |

## Penggunaan

Setelah boot file terdaftar, service disuntikkan sebagai `$pluginsecure` (Options API):

```js
async function verifyLocation () {
  const result = await this.$pluginsecure.verifyGPS()
  if (result.isMock) {
    // tangani mock location
  }
}
```

Atau akses plugin native langsung:

```js
import { registerPlugin } from '@capacitor/core'

const plugin = registerPlugin('PluginSecure')
const photo = await plugin.captureVerifiedPhoto({ requireGPSVerification: true })
```

### API

| Method | Keterangan |
| --- | --- |
| `verifyGPS()` | Mengembalikan `{ isMock, isJailbroken, details }`. |
| `checkDeviceIntegrity()` | Mengembalikan `{ isRooted, isJailbroken, details }`. |
| `captureVerifiedPhoto(options)` | Capture terverifikasi; mengembalikan `{ uri, base64, dataUrl, exif }`. |
| `scanBarcode(options)` | Mengembalikan `{ hasContent, content, format }`. |
| `checkBiometricAvailability()` | Mengembalikan `{ isAvailable, biometryType, reason }`. |
| `authenticateBiometric(options)` | Mengembalikan `{ success, error? }`. |
| `startGPSMonitoring(options)` / `stopGPSMonitoring()` | Pengecekan berkala; memancarkan `gpsMockDetected`. |
| `showNativeError(options)` / `hideNativeError()` | Overlay error native. |
| `saveAppState(options)` / `getLastAppState()` | Simpan / baca rute terakhir. |
| `enableStatePreservation(options)` / `restoreLastAppState()` / `enableNativeFeel()` | Helper state + UX. |
| `checkLocationPermission()` | Mengembalikan `{ granted, state, required }`. |
| `requestLocationPermission()` | Meminta izin lokasi runtime; mengembalikan `{ granted, state }`. |
| `isLocationRequired()` / `setLocationRequired({ enabled })` | Baca / ubah kewajiban lokasi. |
| `openAppSettings()` / `openLocationSettings()` | Buka layar pengaturan app / lokasi. |

### Event

| Event | Payload |
| --- | --- |
| `gpsMockDetected` | `GPSVerificationResult` |
| `deviceIntegrityChanged` | `DeviceIntegrityResult` |
| `nativeErrorRetry` | — |
| `locationPermissionChanged` | `{ granted: boolean }` |

### Gate izin lokasi

Saat `requireLocation` aktif (default), `verifyGPS()`, `captureVerifiedPhoto()` dan `startGPSMonitoring()` terlebih dulu memastikan izin lokasi runtime diberikan: plugin memunculkan prompt OS, lalu melanjutkan call asli. Bila user menolak, call reject dengan kode `LOCATION_PERMISSION_DENIED`.

`src/boot/pluginsecure.js` yang di-generate memasang gate layar penuh yang tidak bisa ditutup selama `isLocationRequired()` bernilai true dan izin belum diberikan, dengan tombol **Pengaturan Lokasi**, **Buka Pengaturan Aplikasi** dan **Periksa Lagi**. Untuk mematikan, set `requireLocation: false` di `capacitor.config.json` lalu jalankan `cap sync` — tanpa ubah kode.

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

### Web / browser (`quasar dev`)

`src/boot/pluginsecure.js` yang di-generate menyediakan fallback browser: `verifyGPS` memakai geolocation browser, `captureVerifiedPhoto` membuka input file, `scanBarcode` meminta input manual. Gate lokasi nonaktif di web. Ini memudahkan pengembangan UI tanpa perangkat.

## Izin Android

Manifest plugin menyumbang berikut (digabung otomatis oleh `cap sync`):

- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.CAMERA`
- `android.permission.USE_BIOMETRIC`
- `android.permission.VIBRATE`

## iOS

Podspec `AlPluginsecure.podspec` dikonsumsi CocoaPods setelah `cap sync`. Butuh Xcode 11+ dan CocoaPods. Tambahkan ke `Info.plist` sesuai kebutuhan:

- `NSLocationWhenInUseUsageDescription` (gate lokasi / verifikasi GPS)
- `NSCameraUsageDescription` (kamera terverifikasi)
- `NSFaceIDUsageDescription` (autentikasi biometrik)

## Troubleshooting

- **`createHash('md4')` / `digital envelope routines::unsupported`** — Node 17+ dengan webpack 4. Set `NODE_OPTIONS=--openssl-legacy-provider`.
- **Gradle/AGP gagal di JDK 17** — build pakai JDK 11: `export JAVA_HOME=$(/usr/libexec/java_home -v 11)`.
- **`Could not find org.apache.cordova:framework:7.0.0`** — jalankan ulang `npx pluginsecure-setup` agar template Android ditambal.
- **Boot file hilang setelah `npm i --ignore-scripts`** — jalankan `node node_modules/@al/pluginsecure/scripts/setup.js`.
- **App masih menampilkan halaman terakhir saat dibuka** — cek `statePreservation` di `capacitor.config.json`; set `false` untuk selalu mulai dari awal.

## Pengembangan

```bash
npm install      # install deps + build + postinstall
npm run build    # dist/esm + dist/cjs
npm run watch    # build inkremental
```

Struktur sumber: `src/index.ts` (web bridge), `src/definitions.ts` (tipe), `src/web.ts`, `src/android`, `src/ios`.

## Lisensi

MIT
