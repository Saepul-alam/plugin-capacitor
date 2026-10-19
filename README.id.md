# @al/pluginsecure

Plugin Capacitor 3 yang menyediakan fitur keamanan native untuk aplikasi hybrid (Quasar v1):

- **Deteksi Fake GPS** — mock location, aplikasi spoofing, emulator (Android) dan indikasi jailbreak (iOS).
- **Kamera terverifikasi** — CameraX (Android) / AVFoundation (iOS) dengan verifikasi GPS dan penanaman EXIF.
- **Gate izin lokasi & kamera** — opsional mewajibkan akses lokasi (gate layar penuh) dan mengarahkan user untuk memberi izin kamera saat fitur kamera dipakai.
- **Integrasi kamera transparan (aktif default)** — boot file yang di-generate mengarahkan `Camera.getPhoto()` dari `@capacitor/camera` ke kamera terverifikasi native dan mem-polyfill `navigator.mediaDevices.getUserMedia()` dengan stream kamera native, sehingga kode wajah/preview yang ada tetap berjalan tanpa diubah. Juga mengekspos API native **burst capture**, **watermark** dan **rekam video**. Semuanya bisa dimatikan satu per satu.
- **Native error UI** — overlay error native untuk 404/5xx, koneksi dan SSL.

> Menargetkan **Capacitor 3** (`@capacitor/core >=3.0.0 <4.0.0`). Terverifikasi pada Quasar v1 (`@quasar/app` v2) mode Capacitor.

> English: [README.md](./README.md)

## Kebutuhan

- Node.js 14+ dan npm 6+.
- **JDK 11** untuk build Android — Gradle 7.0 / AGP 4.2.1 di Capacitor 3.9 tidak mendukung JDK 17.
- Android SDK (`ANDROID_HOME`) dengan build-tools 30.0.3 dan **`compileSdkVersion 31`** — artefak CameraX 1.1.x (camera-video) mendeklarasikan `minCompileSdk=31`.
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
      "requireLocation": true
    }
  }
}
```

| Key | Tipe | Default | Efek |
| --- | --- | --- | --- |
| `requireLocation` | boolean | `true` | Mewajibkan izin lokasi runtime. Bila ditolak, boot file yang di-generate menampilkan gate layar penuh dengan pintasan ke pengaturan app/lokasi. |
| `locationWatch` | boolean | `true` | Jembatani `navigator.geolocation.getCurrentPosition()` / `watchPosition()` ke stream lokasi native realtime (dengan deteksi lokasi palsu). Halaman lama langsung dapat lokasi realtime tanpa ubah kode. |
| `cameraOverride` | boolean | `true` | Arahkan `Camera.getPhoto()` (dari `@capacitor/camera`) ke kamera terverifikasi native. Fail-safe: jika ada error, call asli tetap dijalankan. |
| `cameraStream` | boolean | `true` | Polyfill `navigator.mediaDevices.getUserMedia({ video: true })` dengan stream kamera native (frame → `canvas.captureStream()`). Audio tidak dijembatani. Fallback ke implementasi browser bila gagal. |
| `captureBurst` | boolean | `true` | Aktifkan API native `captureBurst()` (capture multi-frame untuk flow liveness/perspektif). |
| `videoRecorder` | boolean | `true` | Aktifkan API native `startVideoRecording()` / `stopVideoRecording()`. |
| `watermark` | boolean | `true` | Tempel GPS + timestamp ke foto hasil capture secara default. |

Flag bisa ditimpa saat runtime (tersimpan di perangkat):

```js
await this.$pluginsecure.setLocationRequired({ enabled: false })
await this.$pluginsecure.setCameraFeature({ feature: 'cameraOverride', enabled: false })
await this.$pluginsecure.setLocationFeature({ feature: 'locationWatch', enabled: false })
```

> Mengubah `capacitor.config.json` perlu `npx cap sync` (atau jalankan ulang `pluginsecure-setup`).

### Fitur kamera (aktif default)

Boot file yang di-generate memasang dua integrasi transparan begitu plugin native tersedia:

1. **Override `Camera.getPhoto()`.** Setiap panggilan `Camera.getPhoto()` dari `@capacitor/camera` diarahkan ke kamera terverifikasi native lalu dikonversi kembali ke bentuk `Photo` Capacitor (`base64String`, `dataUrl`, `path`, `webPath`, `format`, `exif`). Jika ada yang gagal, call asli dijalankan apa adanya, jadi tidak ada fitur yang mati total.
2. **Polyfill `getUserMedia()`.** `navigator.mediaDevices.getUserMedia({ video: true })` dilayani dari stream kamera native: sisi native mengirim frame JPEG lewat event `cameraFrame` dan boot file menggambarnya ke `<canvas>` yang diekspos lewat `canvas.captureStream()`. Kode live-preview, Quagga / barcode, dan `@vladmandic/face-api` yang ada tetap berjalan **tanpa ubah app**. `applyConstraints({ advanced: [{ torch }] })` dan ganti `facingMode` diemulasi. Frame dikirim **tegak**: `startCameraStream` melaporkan `rotation` device, tiap `cameraFrame` membawanya, dan polyfill menyesuaikan ukuran canvas ke orientasi alami (tukar untuk 90/270). `getCapabilities().torch` pada track mencerminkan unit flash kamera aktif yang sebenarnya. Hanya video: constraint `audio` fallback ke implementasi browser.

API native tambahan (opt-in, semua aktif default lewat flag):

```js
// Capture multi-frame untuk flow liveness / multi-pose (native).
const { frames } = await this.$pluginsecure.captureBurst({ count: 5, intervalMs: 250, direction: 'front' })

// Perekam video native layar penuh (CameraX VideoCapture / AVFoundation).
const { uri, path, durationMs } = await this.$pluginsecure.startVideoRecording({ quality: 'high' })

// Kontrol stream kamera langsung (biasanya ditangani polyfill getUserMedia).
await this.$pluginsecure.startCameraStream({ facingMode: 'environment', width: 640, height: 480 })
await this.$pluginsecure.setCameraTorch({ enabled: true })
await this.$pluginsecure.stopCameraStream()
```

> **Android:** perekam video memakai CameraX 1.1.x, sehingga app host harus menyetel `compileSdkVersion 31` (atau lebih baru). Tanpa itu Gradle gagal di `checkDebugAarMetadata`. Rekam video iOS belum diimplementasikan (method akan reject).

### Lokasi realtime (aktif default)

Selama aplikasi dibuka atau multitasking (di-minimize tapi belum ditutup), plugin mengalirkan lokasi perangkat dari sumber native (FusedLocationProvider / CoreLocation) dan menandai lokasi palsu pada tiap fix.

```js
// Sekali ambil.
const loc = await this.$pluginsecure.getCurrentLocation()
console.log(loc.latitude, loc.longitude, loc.accuracy, loc.isMock)

// Update kontinu (memancarkan `locationUpdate`).
await this.$pluginsecure.startLocationUpdates({ intervalMs: 2000, distanceFilter: 0, highAccuracy: true })

import { registerPlugin } from '@capacitor/core'
const plugin = registerPlugin('PluginSecure')
const handle = await plugin.addListener('locationUpdate', (loc) => {
  // loc: { latitude, longitude, altitude, accuracy, speed, heading, timestamp, provider, ageMs, isMock }
})
// nanti: handle.remove(); await this.$pluginsecure.stopLocationUpdates()
```

- **Foreground service (Android).** `startLocationUpdates` menyalakan foreground service dengan notifikasi permanen (`Melacak lokasi`); ini menahan proses tetap hidup saat app multitasking. Update berhenti otomatis pada `stopLocationUpdates()` dan saat WebView dihancurkan. Tidak perlu `ACCESS_BACKGROUND_LOCATION` ("Allow all the time").
- **iOS.** Tambahkan `UIBackgroundModes: [location]` di `Info.plist` app agar update tetap jalan saat background; indikator lokasi (panah biru) akan tampil.
- **Polyfill `locationWatch`.** Saat flag aktif (default), boot file mengarahkan `navigator.geolocation.getCurrentPosition()` / `watchPosition()` ke stream native, jadi kode lama (mis. helper Leaflet/geolocation) langsung dapat update realtime + deteksi palsu tanpa ubah kode.

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
| `startGPSMonitoring(options)` / `stopGPSMonitoring()` | Pengecekan berkala; memancarkan `gpsMockDetected`. |
| `showNativeError(options)` / `hideNativeError()` | Overlay error native. |
| `checkLocationPermission()` | Mengembalikan `{ granted, state, required }`. |
| `requestLocationPermission()` | Meminta izin lokasi runtime; mengembalikan `{ granted, state }`. |
| `isLocationRequired()` / `setLocationRequired({ enabled })` | Baca / ubah kewajiban lokasi. |
| `getCurrentLocation()` | Ambil lokasi native sekali; mengembalikan `LocationUpdate`. |
| `startLocationUpdates(options)` / `stopLocationUpdates()` | Lokasi native kontinu; memancarkan `locationUpdate`. Opsi: `intervalMs` (2000), `distanceFilter` (0), `highAccuracy` (true). |
| `getLocationConfig()` | Mengembalikan `{ locationWatch }`. |
| `setLocationFeature({ feature, enabled })` | Ubah flag `locationWatch`; mengembalikan config terbaru. |
| `checkCameraPermission()` / `requestCameraPermission()` | Cek / minta izin kamera runtime. |
| `openAppSettings()` / `openLocationSettings()` | Buka layar pengaturan app / lokasi. |
| `getCameraConfig()` | Mengembalikan lima flag fitur kamera. |
| `setCameraFeature({ feature, enabled })` | Ubah satu flag kamera; mengembalikan config terbaru. |
| `captureBurst(options)` | Capture multi-frame native; mengembalikan `{ frames, exif }`. |
| `startCameraStream(options)` / `stopCameraStream()` | Stream kamera native headless yang memancarkan `cameraFrame`. |
| `setCameraTorch({ enabled })` | Kontrol torch untuk stream aktif. |
| `startVideoRecording(options)` / `stopVideoRecording()` | Perekam video native; mengembalikan `{ uri, path, durationMs, hasAudio }`. |

### Event

| Event | Payload |
| --- | --- |
| `gpsMockDetected` | `GPSVerificationResult` |
| `deviceIntegrityChanged` | `DeviceIntegrityResult` |
| `nativeErrorRetry` | — |
| `locationPermissionChanged` | `{ granted: boolean }` |
| `locationUpdate` | `{ latitude: number, longitude: number, altitude: number \| null, accuracy: number, speed: number \| null, heading: number \| null, timestamp: number, provider: string, ageMs: number, isMock: boolean }` |
| `cameraPermissionChanged` | `{ granted: boolean }` |
| `cameraFrame` | `{ base64: string, timestamp: number, rotation?: number, frameWidth?: number, frameHeight?: number, facingMode?: string, torch?: boolean }` |

### Gate izin lokasi

Saat `requireLocation` aktif (default), `verifyGPS()`, `captureVerifiedPhoto()`, `startGPSMonitoring()`, `getCurrentLocation()` dan `startLocationUpdates()` terlebih dulu memastikan izin lokasi runtime diberikan: plugin memunculkan prompt OS, lalu melanjutkan call asli. Bila user menolak, call reject dengan kode `LOCATION_PERMISSION_DENIED`.

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

### Gate izin kamera

Kamera hanya dibutuhkan saat fitur kamera benar-benar dipakai. Saat boot, `src/boot/pluginsecure.js` yang di-generate mengecek izin dan, bila belum ada, menampilkan **banner non-blocking** ("Izin kamera belum aktif"). Saat aksi kamera dijalankan (`getUserMedia` / `Camera.getPhoto`), `CameraGate` memastikan izin: memunculkan prompt OS selama status `prompt`, dan menampilkan overlay layar penuh dengan **Buka Pengaturan Aplikasi**, **Minta Izin Lagi** dan **Tutup** bila izin ditolak. Banner dan overlay otomatis hilang begitu izin diberikan.

Di sisi native, `startCameraStream()` juga meminta izin lalu melanjutkan call (memancarkan `cameraPermissionChanged`); bila ditolak, reject dengan `CAMERA_PERMISSION_DENIED`.

```js
const { granted } = await plugin.checkCameraPermission()
if (!granted) {
  const res = await plugin.requestCameraPermission()
  if (!res.granted) await plugin.openAppSettings()
}
```

### Web / browser (`quasar dev`)

`src/boot/pluginsecure.js` yang di-generate menyediakan fallback browser: `verifyGPS`, `getCurrentLocation` dan `startLocationUpdates` memakai geolocation browser dan `captureVerifiedPhoto` membuka input file (tanpa deteksi palsu di web — `isMock` selalu `false`). Gate lokasi dan kamera nonaktif di web. Ini memudahkan pengembangan UI tanpa perangkat.

## Izin Android

Manifest plugin menyumbang berikut (digabung otomatis oleh `cap sync`):

- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.ACCESS_BACKGROUND_LOCATION` (hanya perlu bila melacak tanpa foreground service)
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_LOCATION`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO` (rekam video native)

## iOS

Podspec `AlPluginsecure.podspec` dikonsumsi CocoaPods setelah `cap sync`. Butuh Xcode 11+ dan CocoaPods. Tambahkan ke `Info.plist` sesuai kebutuhan:

- `NSLocationWhenInUseUsageDescription` (gate lokasi / verifikasi GPS)
- `NSLocationAlwaysAndWhenInUseUsageDescription` (update realtime saat background)
- `UIBackgroundModes` → `location` (update realtime saat background; memunculkan indikator lokasi)
- `NSCameraUsageDescription` (kamera terverifikasi)

## Troubleshooting

- **`createHash('md4')` / `digital envelope routines::unsupported`** — Node 17+ dengan webpack 4. Set `NODE_OPTIONS=--openssl-legacy-provider`.
- **Gradle/AGP gagal di JDK 17** — build pakai JDK 11: `export JAVA_HOME=$(/usr/libexec/java_home -v 11)`.
- **`Could not find org.apache.cordova:framework:7.0.0`** — jalankan ulang `npx pluginsecure-setup` agar template Android ditambal.
- **Boot file hilang setelah `npm i --ignore-scripts`** — jalankan `node node_modules/@al/pluginsecure/scripts/setup.js`.
- **App menampilkan halaman terakhir saat dibuka** — itu karena WebView tetap hidup selama proses masih di background; setelah benar-benar ditutup atau dimatikan OS, app selalu mulai fresh.
- **Lokasi berhenti saat multitasking (Xiaomi/MIUI)** — izinkan app "Autostart" dan set penggunaan baterai "Tanpa pembatasan" di pengaturan perangkat; foreground service (dengan notifikasi permanen) diperlukan dan otomatis dinyalakan oleh `startLocationUpdates()`.

## Pengembangan

```bash
npm install      # install deps + build + postinstall
npm run build    # dist/esm + dist/cjs
npm run watch    # build inkremental
```

Struktur sumber: `src/index.ts` (web bridge), `src/definitions.ts` (tipe), `src/web.ts`, `src/android`, `src/ios`.

## Lisensi

MIT
