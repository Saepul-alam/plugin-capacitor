# capacitor-plugingps-wahana

Plugin Capacitor kustom untuk aplikasi Wahana (termasuk aplikasi operasional kurir SWB) yang menyediakan **pelacakan lokasi real-time berakurasi tinggi**, **deteksi anti-manipulasi lokasi (Anti-Fake GPS / Mock Location)**, dan **sistem gating aplikasi** (memblokir akses operasional jika GPS mati, izin ditolak, atau Fake GPS terdeteksi).

Kompatibel penuh dengan **Capacitor 3.x s/d 8.x**, termasuk **Quasar v1 (`@quasar/app` v2)**, **Vue 2**, dan **Webpack 4**.

---

## Fitur Utama

- ⚡ **Real-time High Accuracy Tracking**: Streaming data koordinat presisi tinggi via `watchPosition` dan event listener `location`.
- 🛡️ **Anti-Fake GPS / Mock Location Detection**: Deteksi otomatis penggunaan aplikasi pemalsu lokasi di level hardware OS (`location.isMock` pada Android 12+, `isFromMockProvider` pada Android lama, dan `isSimulatedBySoftware` pada iOS 15+).
- 🔒 **Gating System Otomatis**: Mendeteksi status gabungan `usable = enabled && granted && !isMock`. Layar aplikasi dapat langsung diblokir seketika jika GPS dimatikan atau Fake GPS dinyalakan.
- 📡 **Instant Provider Broadcast Detection**: Menangkap perubahan saklar GPS di Quick Settings secara instan melalui broadcast receiver tanpa perlu polling.
- ⚙️ **Quick Settings Launcher**: Navigasi langsung ke pengaturan GPS sistem (`openLocationSettings`) dan pengaturan izin aplikasi (`openAppSettings`).

---

## Instalasi

```bash
# Menggunakan npm
npm install capacitor-plugingps-wahana

# Menggunakan yarn
yarn add capacitor-plugingps-wahana

# Sinkronisasi native project
npx cap sync
```

---

## Konfigurasi Platform Native

### 1. Android (`AndroidManifest.xml`)
Tambahkan izin lokasi berikut ke dalam file `android/app/src/main/AndroidManifest.xml` host app Anda:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Izin Lokasi Presisi & Jaringan -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <application ...>
        ...
    </application>
</manifest>
```

> [!NOTE]
> Plugin ini berjalan secara eksklusif dan optimal pada mode foreground, sehingga **tidak membutuhkan** izin latar belakang yang rumit seperti `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, atau deklarasi video di Google Play Store.

### 2. iOS (`Info.plist`)
Tambahkan deskripsi izin lokasi ke dalam file `ios/App/App/Info.plist` host app Anda:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Aplikasi Wahana membutuhkan akses lokasi untuk memvalidasi posisi pengantaran dan penjemputan paket.</string>
```

---

## Panduan Integrasi Gating di Quasar v1 / Vue 2 (`swb`)

Berikut adalah template komponen siap pakai untuk aplikasi `swb`:

```vue
<template>
  <div>
    <!-- GATING DIALOG SWB: Memblokir transaksi jika lokasi tidak usable -->
    <q-dialog :value="!locationStatus.usable" persistent>
      <q-card style="min-width: 320px" class="q-pa-md text-center">
        <q-icon
          :name="locationStatus.isMock ? 'security' : 'location_off'"
          :color="locationStatus.isMock ? 'negative' : 'warning'"
          size="4rem"
        />
        <div class="text-h6 q-mt-sm">
          {{ locationStatus.isMock ? 'Manipulasi Lokasi Terdeteksi!' : 'Akses Lokasi Wajib Aktif' }}
        </div>
        <p class="text-grey-7 q-mt-xs">
          Aplikasi SWB mewajibkan koordinat GPS asli yang aktif untuk memproses pengiriman.
        </p>

        <!-- Banner jika GPS Mati -->
        <q-banner v-if="!locationStatus.enabled" dense class="bg-red-1 text-negative q-mb-sm rounded-borders">
          GPS perangkat Anda saat ini sedang nonaktif.
        </q-banner>

        <!-- Banner jika Izin Belum Diberikan -->
        <q-banner v-if="!locationStatus.granted" dense class="bg-orange-1 text-warning q-mb-sm rounded-borders">
          Izin akses lokasi belum diberikan.
        </q-banner>

        <!-- Banner jika Fake GPS Aktif -->
        <q-banner v-if="locationStatus.isMock" dense class="bg-red-2 text-negative text-bold q-mb-sm rounded-borders">
          Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif! Matikan Fake GPS untuk melanjutkan.
        </q-banner>

        <!-- Tombol Tindakan -->
        <div class="column q-gutter-sm q-mt-md">
          <q-btn v-if="!locationStatus.enabled" color="primary" label="Nyalakan GPS" @click="fixGps" />
          <q-btn v-if="!locationStatus.granted" color="secondary" label="Beri Izin Lokasi" @click="fixPermission" />
          <q-btn flat color="grey-8" label="Cek Ulang Status" @click="refreshStatus" />
        </div>
      </q-card>
    </q-dialog>

    <!-- KONTEN UTAMA SWB -->
    <div v-if="locationStatus.usable">
      <!-- Form pengiriman / absensi kurir -->
    </div>
  </div>
</template>

<script>
import { WahanaPlugin, WahanaGps } from 'capacitor-plugingps-wahana';

export default {
  data() {
    return {
      locationStatus: {
        enabled: true,
        granted: true,
        isMock: false,
        usable: true
      }
    };
  },
  async mounted() {
    // 1. Periksa status lokasi saat komponen dimuat
    this.locationStatus = await WahanaPlugin.checkLocationStatus();

    // 2. Dengarkan perubahan status seketika (toggle GPS on/off, perubahan izin, fake GPS)
    WahanaPlugin.addListener('locationStatusChanged', (status) => {
      this.locationStatus = status;
    });

    // 3. Periksa ulang saat aplikasi kembali dari background / Settings HP
    document.addEventListener('resume', async () => {
      this.locationStatus = await WahanaPlugin.checkLocationStatus();
    });
  },
  methods: {
    async fixGps() {
      await WahanaPlugin.openLocationSettings();
    },
    async fixPermission() {
      await WahanaPlugin.requestPermissions();
      this.locationStatus = await WahanaPlugin.checkLocationStatus();
    },
    async refreshStatus() {
      this.locationStatus = await WahanaPlugin.checkLocationStatus();
    }
  }
};
</script>
```

---

### Opsi Cepat: Native Gating Otomatis Setelah Splash Screen (`enforceLocation`)

Jika ingin setelah splash screen aplikasi langsung meminta izin lokasi dan **mengunci layar secara native (AlertDialog non-cancelable)** jika izin ditolak atau GPS mati:

```javascript
// Di src/App.vue atau boot file Quasar (misal: boot/location-gate.js)
import { WahanaPlugin } from 'capacitor-plugingps-wahana';

export default {
  async mounted() {
    // Cukup 1 baris kode ini!
    // Meminta izin otomatis, cek GPS, deteksi Mock/Fake GPS,
    // dan langsung memblokir layar aplikasi jika izin tidak diberikan atau GPS mati.
    await WahanaPlugin.enforceLocation();
  }
};
```

---

## Matriks Pengujian Verifikasi (6 Skenario)

| No | Skenario Pengujian | Tindakan Pengujian | Hasil yang Diharapkan |
| :---: | :--- | :--- | :--- |
| **1** | **GPS Asli (Normal)** | Jalankan `watchPosition` atau buka aplikasi normal | Koordinat ter-update real-time, `isMock: false`, `usable: true`, aplikasi dapat digunakan. |
| **2** | **Fake GPS Dijalankan** | Aktifkan aplikasi Fake GPS (Mock Location app) | Plugin langsung membaca `isMock: true`, `usable: false`. Dialog pemblokiran otomatis muncul mengunci aplikasi. |
| **3** | **Fake GPS Dimatikan** | Nonaktifkan aplikasi Fake GPS | Status kembali `isMock: false`, `usable: true`. Dialog pemblokiran otomatis tertutup dengan sendirinya. |
| **4** | **GPS Dimatikan di HP** | Tarik Quick Settings HP, matikan tombol Location/GPS | Event `locationStatusChanged` terpancar seketika (`enabled: false`, `usable: false`). Dialog "Nyalakan GPS" langsung mengunci layar. |
| **5** | **Buka Settings & Nyalakan** | Klik "Nyalakan GPS", aktifkan di Settings, kembali ke aplikasi | Layar aplikasi otomatis terbuka kembali tanpa perlu restart aplikasi. |
| **6** | **Izin Ditolak** | Tolak izin lokasi saat pertama kali diminta | Status `granted: false`, `usable: false`. Tombol "Beri Izin Lokasi" memandu user memberikan izin. |

---

## API

<docgen-index>

* [`getCurrentPosition(...)`](#getcurrentposition)
* [`watchPosition(...)`](#watchposition)
* [`stopWatch(...)`](#stopwatch)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions(...)`](#requestpermissions)
* [`isLocationEnabled()`](#islocationenabled)
* [`checkLocationStatus()`](#checklocationstatus)
* [`openLocationSettings()`](#openlocationsettings)
* [`openAppSettings()`](#openappsettings)
* [`addListener('locationStatusChanged', ...)`](#addlistenerlocationstatuschanged-)
* [`addListener('location', ...)`](#addlistenerlocation-)
* [`enforceLocation(...)`](#enforcelocation)
* [`dismissGatingDialog()`](#dismissgatingdialog)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### getCurrentPosition(...)

```typescript
getCurrentPosition(options?: PositionOptions | undefined) => Promise<Position>
```

Get current device position once with anti-mock verification.

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#positionoptions">PositionOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#position">Position</a>&gt;</code>

**Since:** 0.0.1

--------------------


### watchPosition(...)

```typescript
watchPosition(options: WatchPositionOptions, callback: WatchPositionCallback) => Promise<CallbackID>
```

Start watching device position in real-time.

| Param          | Type                                                                    |
| -------------- | ----------------------------------------------------------------------- |
| **`options`**  | <code><a href="#watchpositionoptions">WatchPositionOptions</a></code>   |
| **`callback`** | <code><a href="#watchpositioncallback">WatchPositionCallback</a></code> |

**Returns:** <code>Promise&lt;<a href="#callbackid">CallbackID</a>&gt;</code>

**Since:** 0.0.1

--------------------


### stopWatch(...)

```typescript
stopWatch(options: CallbackID) => Promise<void>
```

Stop watching device position for the given watch ID.

| Param         | Type                                              |
| ------------- | ------------------------------------------------- |
| **`options`** | <code><a href="#callbackid">CallbackID</a></code> |

**Since:** 0.0.1

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<PermissionStatus>
```

Check current location permission status.

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

**Since:** 0.0.1

--------------------


### requestPermissions(...)

```typescript
requestPermissions(permissions?: { permissions: ('location' | 'coarseLocation')[]; } | undefined) => Promise<PermissionStatus>
```

Request location permissions from the user.

| Param             | Type                                                              |
| ----------------- | ----------------------------------------------------------------- |
| **`permissions`** | <code>{ permissions: ('location' \| 'coarseLocation')[]; }</code> |

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

**Since:** 0.0.1

--------------------


### isLocationEnabled()

```typescript
isLocationEnabled() => Promise<{ enabled: boolean; }>
```

Check whether system location services (GPS) are enabled.

**Returns:** <code>Promise&lt;{ enabled: boolean; }&gt;</code>

**Since:** 0.0.1

--------------------


### checkLocationStatus()

```typescript
checkLocationStatus() => Promise<LocationStatus>
```

Check combined location status for gating (enabled, granted, isMock, usable).

**Returns:** <code>Promise&lt;<a href="#locationstatus">LocationStatus</a>&gt;</code>

**Since:** 0.0.1

--------------------


### openLocationSettings()

```typescript
openLocationSettings() => Promise<void>
```

Open the system location / GPS settings page.

**Since:** 0.0.1

--------------------


### openAppSettings()

```typescript
openAppSettings() => Promise<void>
```

Open the application's details / permission settings page.

**Since:** 0.0.1

--------------------


### addListener('locationStatusChanged', ...)

```typescript
addListener(eventName: 'locationStatusChanged', listenerFunc: (status: LocationStatus) => void) => Promise<PluginListenerHandle>
```

Listen for real-time changes in location status (GPS on/off, permission, fake GPS).

| Param              | Type                                                                           |
| ------------------ | ------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'locationStatusChanged'</code>                                           |
| **`listenerFunc`** | <code>(status: <a href="#locationstatus">LocationStatus</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 0.0.1

--------------------


### addListener('location', ...)

```typescript
addListener(eventName: 'location', listenerFunc: (position: Position) => void) => Promise<PluginListenerHandle>
```

Listen for streaming position updates.

| Param              | Type                                                                 |
| ------------------ | -------------------------------------------------------------------- |
| **`eventName`**    | <code>'location'</code>                                              |
| **`listenerFunc`** | <code>(position: <a href="#position">Position</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 0.0.1

--------------------


### enforceLocation(...)

```typescript
enforceLocation(options?: EnforceLocationOptions | undefined) => Promise<LocationStatus>
```

Enforce location gating at startup.
Automatically requests location permissions immediately, checks GPS status,
detects fake GPS, and completely blocks the application if location is not usable.

| Param         | Type                                                                      |
| ------------- | ------------------------------------------------------------------------- |
| **`options`** | <code><a href="#enforcelocationoptions">EnforceLocationOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#locationstatus">LocationStatus</a>&gt;</code>

**Since:** 1.0.0

--------------------


### dismissGatingDialog()

```typescript
dismissGatingDialog() => Promise<void>
```

Manually dismiss any active native gating dialog.

**Since:** 1.0.0

--------------------


### Interfaces


#### Position

| Prop            | Type                                                                | Description                                                |
| --------------- | ------------------------------------------------------------------- | ---------------------------------------------------------- |
| **`timestamp`** | <code>number</code>                                                 | Timestamp in milliseconds when the position was retrieved. |
| **`coords`**    | <code><a href="#locationcoordinates">LocationCoordinates</a></code> | Geographic coordinates and metadata.                       |


#### LocationCoordinates

| Prop                   | Type                        | Description                                                                      |
| ---------------------- | --------------------------- | -------------------------------------------------------------------------------- |
| **`latitude`**         | <code>number</code>         | Latitude in degrees.                                                             |
| **`longitude`**        | <code>number</code>         | Longitude in degrees.                                                            |
| **`accuracy`**         | <code>number</code>         | Accuracy level of the coordinate in meters.                                      |
| **`altitude`**         | <code>number \| null</code> | Altitude in meters above the WGS 84 reference ellipsoid, or null if unavailable. |
| **`altitudeAccuracy`** | <code>number \| null</code> | Accuracy of altitude in meters, or null if unavailable.                          |
| **`heading`**          | <code>number \| null</code> | Direction of travel in degrees (0 - 359.99), or null if unavailable.             |
| **`speed`**            | <code>number \| null</code> | Current velocity in meters per second, or null if unavailable.                   |
| **`isMock`**           | <code>boolean</code>        | True if location is detected to be simulated / fake (Mock Location / Fake GPS).  |


#### PositionOptions

| Prop                     | Type                 | Description                                                                         |
| ------------------------ | -------------------- | ----------------------------------------------------------------------------------- |
| **`enableHighAccuracy`** | <code>boolean</code> | If true, device requests the most accurate location available (GPS). Default: true. |
| **`timeout`**            | <code>number</code>  | Maximum time in milliseconds allowed to return position. Default: 10000ms.          |
| **`maximumAge`**         | <code>number</code>  | Maximum age of a cached location in milliseconds that is acceptable. Default: 0.    |


#### CallbackID

| Prop     | Type                | Description                                                  |
| -------- | ------------------- | ------------------------------------------------------------ |
| **`id`** | <code>string</code> | Identifier returned by watchPosition, used to stop watching. |


#### WatchPositionOptions

| Prop                  | Type                | Description                                                          |
| --------------------- | ------------------- | -------------------------------------------------------------------- |
| **`interval`**        | <code>number</code> | Desired update interval in milliseconds (Android). Default: 3000ms.  |
| **`fastestInterval`** | <code>number</code> | Fastest update interval in milliseconds (Android). Default: 1000ms.  |
| **`minimumDistance`** | <code>number</code> | Minimum distance displacement in meters before updating. Default: 0. |


#### PermissionStatus

| Prop                 | Type                                                        | Description                           |
| -------------------- | ----------------------------------------------------------- | ------------------------------------- |
| **`location`**       | <code><a href="#permissionstate">PermissionState</a></code> | Status of fine location permission.   |
| **`coarseLocation`** | <code><a href="#permissionstate">PermissionState</a></code> | Status of coarse location permission. |


#### LocationStatus

| Prop          | Type                 | Description                                                                                    |
| ------------- | -------------------- | ---------------------------------------------------------------------------------------------- |
| **`enabled`** | <code>boolean</code> | True if system location services (GPS) are enabled on the device.                              |
| **`granted`** | <code>boolean</code> | True if app has been granted location permissions.                                             |
| **`isMock`**  | <code>boolean</code> | True if a mock location provider (Fake GPS) is currently active.                               |
| **`usable`**  | <code>boolean</code> | True if location is fully usable (enabled && granted && !isMock). Used for application gating. |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### EnforceLocationOptions

| Prop                          | Type                | Description                                                             |
| ----------------------------- | ------------------- | ----------------------------------------------------------------------- |
| **`title`**                   | <code>string</code> | Dialog title when gating is active.                                     |
| **`messagePermissionDenied`** | <code>string</code> | Custom message displayed when permission is denied.                     |
| **`messageGpsDisabled`**      | <code>string</code> | Custom message displayed when GPS service is disabled.                  |
| **`messageMockDetected`**     | <code>string</code> | Custom message displayed when Fake GPS / mock location is detected.     |
| **`actionButtonLabel`**       | <code>string</code> | Label for the action button (e.g. 'Nyalakan GPS' or 'Buka Pengaturan'). |
| **`exitButtonLabel`**         | <code>string</code> | Label for the exit button.                                              |


### Type Aliases


#### WatchPositionCallback

<code>(position: <a href="#position">Position</a> | null, err?: any): void</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>

</docgen-api>
