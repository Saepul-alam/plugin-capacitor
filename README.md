# capacitor-plugingps-wahana

Plugin Capacitor kustom untuk aplikasi Wahana (khususnya aplikasi operasional kurir SWB) yang menyediakan **pelacakan lokasi real-time berakurasi tinggi**, **deteksi anti-manipulasi lokasi (Anti-Fake GPS / Mock Location)**, dan **sistem penguncian native otomatis (Gating System)** yang langsung memblokir akses aplikasi seketika setelah splash screen jika GPS mati, izin ditolak, atau Fake GPS terdeteksi.

Ditulis **100% dalam Kotlin** dan kompatibel penuh dengan **Capacitor 2 (Quasar v1 / SWB)** maupun **Capacitor 3.x s/d 8.x**.

---

## 🚀 Fitur Utama

- 🔒 **Zero-Action Native Startup Gating**: Seketika setelah splash screen terbuka, plugin otomatis memeriksa izin dan status GPS. Jika GPS mati atau izin ditolak, muncul dialog native non-cancelable yang mengunci layar aplikasi (aplikasi tidak bisa digunakan sampai persyaratan dipenuhi).
- ⚡ **Real-time High Accuracy Tracking**: Pelacakan koordinat presisi tinggi (1–3 detik) berbasis Google Fused Location Provider tanpa boros baterai.
- 🛡️ **Anti-Fake GPS / Mock Location Detection**: Deteksi otomatis aplikasi pemalsu lokasi di level sistem operasi (`location.isMock` pada Android 12+, `isFromMockProvider` pada Android lama, dan `isSimulatedBySoftware` pada iOS 15+).
- 📡 **Instant Broadcast Receiver**: Menangkap saklar on/off GPS di Quick Settings secara instan tanpa perlu polling interval.
- ☕ **Zero-Config Kotlin Compatibility**: Bytecode Kotlin dipaketkan langsung sehingga aman dikompilasi pada proyek lama (seperti AGP 3.6.1 + Gradle 7.0) tanpa error `KotlinBasePlugin`.

---

## 📦 Cara Instalasi

### 1. Instal Plugin via GitHub
Jalankan perintah berikut di direktori proyek Anda (atau di folder `src-capacitor`):

```bash
npm install https://github.com/Saepul-alam/plugin-capacitor.git#w.1.0.0 --force
```

Lalu sinkronkan Capacitor ke Android:
```bash
npx cap sync android
```

---

## ⚙️ Konfigurasi Native Android

### A. Untuk Capacitor 2 (Proyek Quasar v1 / `swb`)

1. **Daftarkan Plugin di `MainActivity.java`**
   Buka file `android/app/src/main/java/.../MainActivity.java`:
   ```java
   package com.wahana.swbrc;

   import android.os.Bundle;
   import com.getcapacitor.BridgeActivity;
   import com.getcapacitor.Plugin;
   import com.wahana.plugingps.WahanaPluginPlugin; // <--- 1. Import plugin

   import java.util.ArrayList;

   public class MainActivity extends BridgeActivity {
     @Override
     public void onCreate(Bundle savedInstanceState) {
       super.onCreate(savedInstanceState);

       // Daftarkan plugin di dalam ArrayList init
       this.init(savedInstanceState, new ArrayList<Class<? extends Plugin>>() {{
         add(WahanaPluginPlugin.class); // <--- 2. Daftarkan di sini
       }});
     }
   }
   ```

2. **Mirror Repository di `android/build.gradle` (Penting)**
   Karena JCenter sudah ditutup permanen, tambahkan mirror Grails pada blok `allprojects.repositories` di `android/build.gradle` agar dependensi legacy Cordova dapat terunduh:
   ```groovy
   allprojects {
       repositories {
           google()
           mavenCentral()
           maven { url 'https://repo.grails.org/grails/core' }
           jcenter()
       }
   }
   ```

---

### B. Untuk Capacitor 3 ke Atas (Capacitor 3, 4, 5, 6, 7, 8)
Pada Capacitor 3+, pendaftaran berjalan **otomatis**. Anda cukup menjalankan:
```bash
npx cap sync android
```
Tanpa perlu mengubah file `MainActivity.java`.

---

## 🛠️ Cara Build Android

Karena Gradle 7.0 pada project ini memerlukan Java 11:

```bash
# 1. Masuk ke folder android
cd android

# 2. Build APK Debug
JAVA_HOME=$(/usr/libexec/java_home -v 11) ./gradlew assembleDebug
```

File APK siap install akan berada di:
`android/app/build/outputs/apk/debug/app-debug.apk`

> [!TIP]
> **Jika build menggunakan Quasar CLI (`@quasar/app` v2 di Node 17+):**
> Sertakan `NODE_OPTIONS=--openssl-legacy-provider` dan `JAVA_HOME`:
> ```bash
> JAVA_HOME=$(/usr/libexec/java_home -v 11) NODE_OPTIONS=--openssl-legacy-provider npx quasar build -m capacitor -T android
> ```

---

## 💻 Contoh Penggunaan di Vue / JavaScript

### 1. Pelacakan Real-time Presisi Tinggi
```javascript
import { Plugins } from '@capacitor/core';
const { WahanaPlugin } = Plugins;

// Mulai tracking real-time
const watchId = await WahanaPlugin.watchPosition(
  {
    enableHighAccuracy: true,
    timeout: 5000,
    maximumAge: 0
  },
  (position, err) => {
    if (err) {
      console.error('Error GPS:', err);
      return;
    }
    console.log('Latitude:', position.coords.latitude);
    console.log('Longitude:', position.coords.longitude);
    console.log('Akurasi (m):', position.coords.accuracy);
    console.log('Apakah Fake GPS?:', position.isMock);
  }
);

// Hentikan tracking jika sudah selesai
// await WahanaPlugin.stopWatch({ id: watchId });
```

### 2. Mengambil Posisi Satu Kali (Sekaligus Verifikasi Fake GPS)
```javascript
import { Plugins } from '@capacitor/core';
const { WahanaPlugin } = Plugins;

try {
  const position = await WahanaPlugin.getCurrentPosition({
    enableHighAccuracy: true,
    timeout: 10000
  });

  if (position.isMock) {
    alert('Peringatan: Fake GPS terdeteksi!');
  } else {
    console.log('Lokasi valid:', position.coords.latitude, position.coords.longitude);
  }
} catch (error) {
  console.error('Gagal mengambil lokasi:', error);
}
```

### 3. Memeriksa Status Lokasi & GPS
```javascript
import { Plugins } from '@capacitor/core';
const { WahanaPlugin } = Plugins;

const status = await WahanaPlugin.checkLocationStatus();
console.log(status);
// Output:
// {
//   enabled: true,   // GPS HP aktif
//   granted: true,   // Izin lokasi diberikan
//   isMock: false,   // Bukan Fake GPS
//   usable: true     // Siap digunakan untuk transaksi
// }
```

### 4. Menjalankan Native Gating secara Manual
Jika ingin memicu dialog penguncian native kapan saja:
```javascript
await WahanaPlugin.enforceLocation({
  title: 'Akses Lokasi Wajib Aktif',
  messagePermissionDenied: 'Aplikasi SWB membutuhkan izin lokasi presisi agar dapat digunakan.',
  messageGpsDisabled: 'Layanan GPS HP Anda sedang mati. Silakan nyalakan GPS untuk melanjutkan.',
  messageMockDetected: 'Fake GPS terdeteksi aktif! Matikan aplikasi Fake GPS untuk melanjutkan.',
  actionButtonLabel: 'Buka Pengaturan',
  exitButtonLabel: 'Keluar'
});
```

---

## 🧪 Matriks Pengujian Skenario

| No | Skenario Pengujian | Tindakan Pengujian | Hasil yang Diharapkan |
| :---: | :--- | :--- | :--- |
| **1** | **GPS Asli Aktif** | Buka aplikasi dalam kondisi GPS menyala | Aplikasi berjalan normal, koordinat terupdate real-time, `isMock: false`. |
| **2** | **GPS Mati Saat Buka Aplikasi** | Buka aplikasi dengan GPS mati | Muncul dialog native non-cancelable. Tombol "Nyalakan GPS" membuka Settings, tombol "Keluar" menutup aplikasi. |
| **3** | **GPS Dimatikan di Tengah Operasional** | Matikan tombol GPS di Quick Settings HP | Dialog penguncian langsung muncul seketika secara otomatis. |
| **4** | **GPS Dinyalakan Kembali** | Aktifkan GPS dari Quick Settings / Settings | Dialog pengunci otomatis tertutup dan aplikasi dapat digunakan kembali. |
| **5** | **Fake GPS Dijalankan** | Aktifkan aplikasi Fake GPS (Mock Location) | Plugin mendeteksi `isMock: true` dan memblokir layar aplikasi. |
| **6** | **Izin Lokasi Ditolak** | Tolak izin lokasi saat pertama kali diminta | Aplikasi mengunci layar dan memandu membuka Pengaturan Aplikasi. |

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
