#!/usr/bin/env node
/*
 * Postinstall hook for @al/pluginsecure (Capacitor 3).
 *
 * IMPORTANT: this hook ONLY drops the Quasar boot file. It deliberately does
 * NOT run `npm install`, `cap add` or `cap sync` on its own: spawning npm from
 * inside a lifecycle script races with the ongoing install and the top-level
 * npm rewrites package.json afterwards, reverting any change we make. Run the
 * full native setup explicitly instead (scripts/setup.js also does the boot
 * file, so this hook stays a no-op when it is already registered):
 *
 *     cd src-capacitor
 *     npm i @al/pluginsecure
 *     npx pluginsecure-setup
 *
 * `pluginsecure-setup` upgrades src-capacitor to Capacitor 3, adds the
 * platforms, runs `cap sync` (which generates capacitor.plugins.json so
 * Capacitor 3 auto-registers @CapacitorPlugin classes) and writes the boot file.
 *
 * Set PLUGINSECURE_SKIP_POSTINSTALL=1 to disable the boot file generation.
 */
'use strict'

const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')

const TAG = '[pluginsecure]'
const log = (message) => console.log(`${TAG} ${message}`)
const warn = (message) => console.warn(`${TAG} ${message}`)

const SKIP = process.env.PLUGINSECURE_SKIP_POSTINSTALL
const SKIP_POD = process.env.PLUGINSECURE_SKIP_POD_INSTALL
const SKIP_IOS = process.env.PLUGINSECURE_SKIP_IOS
const NESTED_INSTALL = process.env.PLUGINSECURE_NESTED_INSTALL

const CAP_MAJOR = 3
const CAP_RANGE = '^3.9.0'

function run (command, args, cwd, extraEnv) {
  log(`running: ${command} ${args.join(' ')} (cwd: ${cwd})`)
  const result = spawnSync(command, args, {
    cwd,
    stdio: 'inherit',
    shell: process.platform === 'win32',
    env: { ...process.env, ...(extraEnv || {}) }
  })
  return result.status === 0
}

// --- 1. Locate the Capacitor project ----------------------------------------

function findCapacitorDir (startDir) {
  if (fs.existsSync(path.join(startDir, 'capacitor.config.json')) ||
      fs.existsSync(path.join(startDir, 'capacitor.config.ts'))) {
    return startDir
  }
  // One level up covers `npm i` invoked from a subfolder of the cap project.
  const parent = path.dirname(startDir)
  if (parent !== startDir &&
      (fs.existsSync(path.join(parent, 'capacitor.config.json')) ||
       fs.existsSync(path.join(parent, 'capacitor.config.ts')))) {
    return parent
  }
  return null
}

function findQuasarRoot (startDir) {
  for (const dir of [startDir, path.dirname(startDir)]) {
    if (fs.existsSync(path.join(dir, 'quasar.conf.js')) ||
        fs.existsSync(path.join(dir, 'quasar.config.js'))) {
      return dir
    }
  }
  return null
}

// --- 2. Upgrade the native project to Capacitor 3 ---------------------------

function installedMajor (capDir, name) {
  try {
    const pkg = require(path.join(capDir, 'node_modules', name, 'package.json'))
    return parseInt(String(pkg.version).split('.')[0], 10)
  } catch (_) {
    return null
  }
}

function ensureCapacitor3 (capDir) {
  const major = installedMajor(capDir, '@capacitor/core')
  if (major !== null && major >= CAP_MAJOR) {
    log(`Capacitor ${major} already installed, no upgrade needed`)
    return
  }

  const pkgPath = path.join(capDir, 'package.json')
  if (!fs.existsSync(pkgPath)) {
    warn(`no ${pkgPath}, skipping Capacitor upgrade`)
    return
  }

  log('default Capacitor 2 detected -> upgrading src-capacitor to Capacitor 3')
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'))
  pkg.dependencies = pkg.dependencies || {}

  pkg.dependencies['@capacitor/core'] = CAP_RANGE
  pkg.dependencies['@capacitor/cli'] = CAP_RANGE
  if (!pkg.dependencies['@capacitor/android']) pkg.dependencies['@capacitor/android'] = CAP_RANGE
  if (!pkg.dependencies['@capacitor/ios']) pkg.dependencies['@capacitor/ios'] = CAP_RANGE

  fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n')
  run('npm', ['install'], capDir, { PLUGINSECURE_NESTED_INSTALL: '1' })
}

// --- 3/4/5. Platforms + sync ------------------------------------------------

function capBin (capDir) {
  const bin = process.platform === 'win32' ? 'cap.cmd' : 'cap'
  const local = path.join(capDir, 'node_modules', '.bin', bin)
  return fs.existsSync(local) ? { command: local, prefix: [] } : { command: 'npx', prefix: ['cap'] }
}

function cap (capDir, args) {
  const { command, prefix } = capBin(capDir)
  return run(command, prefix.concat(args), capDir)
}

function ensureWww (capDir) {
  const www = path.join(capDir, 'www')
  if (fs.existsSync(path.join(www, 'index.html'))) return
  fs.mkdirSync(www, { recursive: true })
  fs.writeFileSync(
    path.join(www, 'index.html'),
    '<!DOCTYPE html>\n<html>\n  <head>\n    <meta charset="utf-8">\n    <title>App</title>\n  </head>\n  <body></body>\n</html>\n'
  )
  log('created placeholder www/index.html so `cap add` can run')
}

function findPodDir (iosDir) {
  for (const rel of ['App', '.']) {
    const dir = path.join(iosDir, rel)
    if (fs.existsSync(path.join(dir, 'Podfile'))) return dir
  }
  return null
}

// Capacitor 3.9's bundled Android template pins org.apache.cordova:framework to
// 7.0.0 (only ever published to the now-defunct jcenter) and defaults minSdk to
// 21. Both break `gradlew assembleDebug` today. Bump cordova to a Maven Central
// release, raise minSdk to 22 (cordova 10.x requirement) and make sure
// mavenCentral() is on the repository list.
function patchAndroidProject (capDir) {
  const androidDir = path.join(capDir, 'android')

  const variables = path.join(androidDir, 'variables.gradle')
  if (fs.existsSync(variables)) {
    const source = fs.readFileSync(variables, 'utf8')
    let next = source.replace(
      /(cordovaAndroidVersion\s*=\s*')(\d+)\.\d+\.\d+(')/,
      (match, head, major, tail) => (Number(major) < 10 ? `${head}10.1.1${tail}` : match)
    )
    next = next.replace(
      /(minSdkVersion\s*=\s*)(\d+)/,
      (match, head, value) => (Number(value) < 22 ? `${head}22` : match)
    )
    // CameraX 1.1.x (camera-video) declares minCompileSdk=31.
    next = next.replace(
      /(compileSdkVersion\s*=\s*)(\d+)/,
      (match, head, value) => (Number(value) < 31 ? `${head}31` : match)
    )
    if (next !== source) {
      fs.writeFileSync(variables, next)
      log('patched android/variables.gradle (cordova 10.1.1, minSdk >= 22, compileSdk >= 31)')
    }
  }

  const rootGradle = path.join(androidDir, 'build.gradle')
  if (fs.existsSync(rootGradle)) {
    const source = fs.readFileSync(rootGradle, 'utf8')
    if (!/mavenCentral\(\)/.test(source)) {
      const next = source.replace(/^(\s*)google\(\)$/gm, '$1google()\n$1mavenCentral()')
      if (next !== source) {
        fs.writeFileSync(rootGradle, next)
        log('patched android/build.gradle (added mavenCentral)')
      }
    }
  }
}

function syncNative (capDir) {
  ensureWww(capDir)

  if (!fs.existsSync(path.join(capDir, 'android'))) {
    log('adding android platform')
    if (!cap(capDir, ['add', 'android'])) {
      warn('`cap add android` failed; run it manually before building')
    }
  }

  patchAndroidProject(capDir)

  if (process.platform === 'darwin' && !SKIP_IOS && !fs.existsSync(path.join(capDir, 'ios'))) {
    log('adding ios platform')
    if (!cap(capDir, ['add', 'ios'])) {
      warn('`cap add ios` failed; run it manually before building')
    }
  }

  // Capacitor 3 auto-registers npm plugins from capacitor.plugins.json, which
  // is generated here. No MainActivity edit is required.
  if (!cap(capDir, ['sync'])) {
    warn('cap sync failed; run `npx cap sync` manually')
    return
  }

  const iosDir = path.join(capDir, 'ios')
  if (fs.existsSync(iosDir) && process.platform === 'darwin' && !SKIP_POD) {
    const podDir = findPodDir(iosDir)
    if (podDir) {
      run('pod', ['install'], podDir)
    } else {
      warn('ios/ found but no Podfile, skipping pod install')
    }
  }
}

// --- 6. Quasar boot file -----------------------------------------------------

const BOOT_FILENAME = 'pluginsecure.js'

function bootFileSource () {
  return `/*
 * Auto-generated by @al/pluginsecure. Safe to edit, but ` +
`re-running \`npm i @al/pluginsecure\` may overwrite it.
 *
 * Exposes a \`$pluginsecure\` service that talks to the native PluginSecure
 * plugin and falls back to browser implementations on web (quasar dev).
 */
import { Capacitor, registerPlugin } from '@capacitor/core'

const PluginSecure = registerPlugin('PluginSecure')

function isNative () {
  return typeof Capacitor.isNativePlatform === 'function'
    ? Capacitor.isNativePlatform()
    : Capacitor.platform === 'android' || Capacitor.platform === 'ios'
}

function hasMethod (method) {
  return isNative() && PluginSecure && typeof PluginSecure[method] === 'function'
}

async function verifyGPS () {
  if (hasMethod('verifyGPS')) {
    return PluginSecure.verifyGPS()
  }
  return new Promise((resolve) => {
    if (typeof navigator !== 'undefined' && navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => resolve({
          isMock: false,
          isJailbroken: false,
          details: {
            mockLocationEnabled: false,
            isFromMockProvider: false,
            suspiciousApps: [],
            locationProvider: 'web-browser',
            accuracy: pos.coords.accuracy,
            latitude: pos.coords.latitude,
            longitude: pos.coords.longitude,
            timestamp: pos.timestamp,
            isSimulator: false
          }
        }),
        (err) => resolve({
          isMock: false,
          isJailbroken: false,
          details: {
            mockLocationEnabled: false,
            isFromMockProvider: false,
            suspiciousApps: [],
            locationProvider: 'none',
            accuracy: -1,
            timestamp: Date.now(),
            isSimulator: false,
            error: err.message
          }
        }),
        { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }
      )
    } else {
      resolve({ isMock: false, isJailbroken: false, details: { accuracy: -1, timestamp: Date.now() } })
    }
  })
}

async function captureVerifiedPhoto (options = {}) {
  if (hasMethod('captureVerifiedPhoto')) {
    return PluginSecure.captureVerifiedPhoto(options)
  }
  return new Promise((resolve, reject) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    input.capture = options.direction === 'front' ? 'user' : 'environment'
    input.onchange = (event) => {
      const file = event.target.files && event.target.files[0]
      if (!file) { reject(new Error('No photo selected')); return }
      const reader = new FileReader()
      reader.onload = () => {
        const dataUrl = reader.result
        resolve({
          uri: dataUrl,
          base64: dataUrl.split(',')[1] || '',
          dataUrl,
          exif: {
            latitude: 0,
            longitude: 0,
            altitude: 0,
            accuracy: 10,
            timestamp: Date.now(),
            isVerified: true,
            verificationDetails: 'WEB_FALLBACK_BROWSER'
          }
        })
      }
      reader.onerror = () => reject(new Error('Failed to read photo file'))
      reader.readAsDataURL(file)
    }
    input.click()
  })
}

async function scanBarcode (options = {}) {
  if (hasMethod('scanBarcode')) {
    return PluginSecure.scanBarcode(options)
  }
  const simulated = typeof window !== 'undefined'
    ? window.prompt(options.prompt || 'Scan Barcode / Resi (Web Dev Fallback):', '')
    : null
  if (simulated) {
    return { hasContent: true, content: simulated.trim(), format: 'WEB_SIMULATED' }
  }
  return { hasContent: false, content: '', format: 'NONE' }
}

function passThrough (method, fallback) {
  return async (options) => {
    if (hasMethod(method)) {
      return PluginSecure[method](options)
    }
    return typeof fallback === 'function' ? fallback(options) : fallback
  }
}

// --- Camera features (generic, auto-on) -------------------------------------
// All flags default to true and can be disabled from capacitor.config.json or
// at runtime via setCameraFeature({ feature, enabled }).
let cameraConfig = {
  cameraOverride: true,
  cameraStream: true,
  captureBurst: true,
  videoRecorder: true,
  watermark: true
}

async function refreshCameraConfig () {
  if (!hasMethod('getCameraConfig')) return cameraConfig
  try {
    cameraConfig = await PluginSecure.getCameraConfig()
  } catch (e) { /* keep defaults */ }
  return cameraConfig
}

async function setCameraFeature (feature, enabled) {
  if (hasMethod('setCameraFeature')) {
    cameraConfig = await PluginSecure.setCameraFeature({ feature, enabled: !!enabled })
  } else {
    cameraConfig = Object.assign({}, cameraConfig, { [feature]: !!enabled })
  }
  return cameraConfig
}

async function captureBurst (options = {}) {
  if (hasMethod('captureBurst')) {
    return PluginSecure.captureBurst(options)
  }
  return {
    frames: [],
    exif: { latitude: 0, longitude: 0, altitude: 0, accuracy: -1, timestamp: Date.now(), isVerified: false, verificationDetails: 'WEB_UNAVAILABLE' }
  }
}

async function startCameraStream (options = {}) {
  if (hasMethod('startCameraStream')) return PluginSecure.startCameraStream(options)
  return { started: false }
}

async function stopCameraStream () {
  if (hasMethod('stopCameraStream')) return PluginSecure.stopCameraStream()
}

async function setCameraTorch (enabled) {
  if (hasMethod('setCameraTorch')) return PluginSecure.setCameraTorch({ enabled: !!enabled })
}

async function startVideoRecording (options = {}) {
  if (hasMethod('startVideoRecording')) return PluginSecure.startVideoRecording(options)
  return { started: false }
}

async function stopVideoRecording () {
  if (hasMethod('stopVideoRecording')) return PluginSecure.stopVideoRecording()
  return { uri: '', durationMs: 0, hasAudio: false }
}

export const PluginSecureService = {
  verifyGPS,
  captureVerifiedPhoto,
  scanBarcode,
  checkDeviceIntegrity: passThrough('checkDeviceIntegrity', { isRooted: false, isJailbroken: false, details: ['web'] }),
  checkBiometricAvailability: passThrough('checkBiometricAvailability', { isAvailable: false, biometryType: 'none' }),
  authenticateBiometric: passThrough('authenticateBiometric', { success: true }),
  startGPSMonitoring: passThrough('startGPSMonitoring'),
  stopGPSMonitoring: passThrough('stopGPSMonitoring'),
  showNativeError: passThrough('showNativeError'),
  hideNativeError: passThrough('hideNativeError'),
  saveAppState: passThrough('saveAppState'),
  getLastAppState: passThrough('getLastAppState', { isExpired: true }),
  enableStatePreservation: passThrough('enableStatePreservation'),
  restoreLastAppState: passThrough('restoreLastAppState', { restored: false }),
  enableNativeFeel: passThrough('enableNativeFeel'),
  checkLocationPermission: passThrough('checkLocationPermission', { granted: false, state: 'denied', required: false }),
  requestLocationPermission: passThrough('requestLocationPermission', { granted: false, state: 'denied', required: false }),
  isLocationRequired: passThrough('isLocationRequired', { enabled: false }),
  setLocationRequired: passThrough('setLocationRequired', { enabled: false }),
  openAppSettings: passThrough('openAppSettings'),
  openLocationSettings: passThrough('openLocationSettings'),
  getCameraConfig: refreshCameraConfig,
  setCameraFeature: ({ feature, enabled } = {}) => setCameraFeature(feature, enabled),
  captureBurst,
  startCameraStream,
  stopCameraStream,
  setCameraTorch: ({ enabled } = {}) => setCameraTorch(enabled),
  startVideoRecording,
  stopVideoRecording
}

// Location gate. Controlled entirely from capacitor.config.json:
//   plugins.PluginSecure.requireLocation  (default true)
// read through the native isLocationRequired(). Runtime override:
// setLocationRequired({ enabled }).
const LOCATION_GATE_ID = '__pluginsecure_location_gate__'

const BUTTON_STYLE = [
  'display:block', 'width:100%', 'margin:0 0 10px', 'padding:14px 16px',
  'border:0', 'border-radius:10px', 'font-size:15px', 'font-weight:600',
  'cursor:pointer'
].join(';')

const LocationGate = {
  overlay: null,
  active: false,
  busy: false,

  async init () {
    if (!isNative() || typeof document === 'undefined') return

    window.addEventListener('focus', () => { this.refresh() })
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') this.refresh()
    })
    if (PluginSecure && typeof PluginSecure.addListener === 'function') {
      try {
        PluginSecure.addListener('locationPermissionChanged', () => { this.refresh() })
      } catch (e) { /* listener not supported */ }
    }
    await this.refresh()
  },

  async refresh () {
    if (this.busy || typeof document === 'undefined') return
    this.busy = true
    try {
      const required = await PluginSecureService.isLocationRequired()
      if (!required || required.enabled === false) { this.hide(); return }

      let perm = await PluginSecureService.checkLocationPermission()
      if (perm && perm.state === 'prompt') {
        perm = await PluginSecureService.requestLocationPermission()
      }
      if (perm && perm.granted) {
        this.hide()
      } else {
        this.show()
      }
    } catch (e) {
      // Fail open: never lock the UI out because of an unexpected error.
    } finally {
      this.busy = false
    }
  },

  show () {
    if (this.active || typeof document === 'undefined' || !document.body) return
    this.active = true

    const el = document.createElement('div')
    el.id = LOCATION_GATE_ID
    el.setAttribute('style', [
      'position:fixed', 'top:0', 'right:0', 'bottom:0', 'left:0',
      'z-index:2147483647', 'display:flex', 'align-items:center',
      'justify-content:center', 'padding:24px', 'box-sizing:border-box',
      'text-align:center', 'background:#0f172a', 'color:#f8fafc',
      'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif'
    ].join(';'))
    el.innerHTML =
      '<div style="max-width:360px;width:100%">' +
      '<h2 style="margin:0 0 12px;font-size:20px">Izin Lokasi Diperlukan</h2>' +
      '<p style="margin:0 0 22px;font-size:14px;line-height:1.5;opacity:.85">' +
      'Aplikasi ini memerlukan akses lokasi untuk dapat digunakan. ' +
      'Aktifkan izin lokasi pada pengaturan perangkat, lalu periksa kembali.' +
      '</p>' +
      '<button data-action="location" style="' + BUTTON_STYLE + ';background:#2563eb;color:#fff">Pengaturan Lokasi</button>' +
      '<button data-action="app" style="' + BUTTON_STYLE + ';background:#334155;color:#fff">Buka Pengaturan Aplikasi</button>' +
      '<button data-action="recheck" style="' + BUTTON_STYLE + ';background:transparent;color:#93c5fd;border:1px solid #334155">Periksa Lagi</button>' +
      '</div>'

    el.addEventListener('click', (event) => {
      const target = event.target
      const btn = target && target.closest ? target.closest('button[data-action]') : null
      if (!btn) return
      const action = btn.getAttribute('data-action')
      if (action === 'location') {
        PluginSecureService.openLocationSettings()
      } else if (action === 'app') {
        PluginSecureService.openAppSettings()
      } else {
        this.refresh()
      }
    })

    document.body.appendChild(el)
    this.overlay = el
  },

  hide () {
    this.active = false
    if (this.overlay && this.overlay.parentNode) {
      this.overlay.parentNode.removeChild(this.overlay)
    }
    this.overlay = null
  }
}

// --- @capacitor/camera override (auto) --------------------------------------
// Intercepts Camera.getPhoto at the Capacitor bridge level and routes it to the
// native secure camera. Fail-safe: any error falls back to the original call.
function mapPhotoOptions (options = {}) {
  const out = {
    quality: options.quality != null ? options.quality : 90,
    direction: options.direction === 'front' ? 'front' : 'rear',
    resultType: options.resultType === 'dataUrl' ? 'dataUrl' : 'base64',
    requireGPSVerification: false
  }
  return out
}

function toCapacitorPhoto (res) {
  const raw = res || {}
  const base64 = raw.base64 || (raw.dataUrl ? String(raw.dataUrl).split(',')[1] : '')
  return {
    base64String: base64 || undefined,
    dataUrl: raw.dataUrl || (base64 ? 'data:image/jpeg;base64,' + base64 : undefined),
    path: raw.path,
    webPath: raw.uri,
    format: 'jpeg',
    saved: false,
    exif: raw.exif
  }
}

const CameraOverride = {
  installed: false,

  install () {
    if (this.installed || !isNative() || typeof Capacitor === 'undefined') return
    const cap = Capacitor
    if (typeof cap.nativePromise !== 'function') return
    const before = cap.nativePromise
    const originalNativePromise = before.bind(cap)
    try {
      cap.nativePromise = function (pluginName, methodName, options) {
        if (
          pluginName === 'Camera' &&
          methodName === 'getPhoto' &&
          cameraConfig.cameraOverride !== false &&
          PluginSecure && typeof PluginSecure.captureVerifiedPhoto === 'function'
        ) {
          return PluginSecure
            .captureVerifiedPhoto(mapPhotoOptions(options))
            .then(toCapacitorPhoto)
        }
        return originalNativePromise(pluginName, methodName, options)
      }
    } catch (e) { /* nativePromise is read-only, leave it alone */ }
    this.installed = cap.nativePromise !== before
  }
}

// --- getUserMedia polyfill (auto) -------------------------------------------
// Bridges navigator.mediaDevices.getUserMedia to a native camera stream exposed
// as a canvas.captureStream(). Live preview, barcode scanning and face-api keep
// working unchanged. Audio constraints are ignored (video only) and any failure
// falls back to the original browser implementation.
const CameraStream = {
  installed: false,
  original: null,
  stream: null,
  canvas: null,
  ctx: null,
  canvasStream: null,
  frameListener: null,
  facingMode: 'environment',

  install () {
    if (this.installed || !isNative()) return
    if (typeof navigator === 'undefined' || !navigator.mediaDevices) return
    if (typeof navigator.mediaDevices.getUserMedia !== 'function') return
    if (!hasMethod('startCameraStream')) return
    this.original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
    this.installed = true
    const self = this
    navigator.mediaDevices.getUserMedia = function (constraints) {
      return self.handle(constraints)
    }
  },

  async handle (constraints) {
    const video = constraints && constraints.video
    if (!video || cameraConfig.cameraStream === false) {
      return this.original(constraints)
    }
    try {
      return await this.startNativeStream(constraints)
    } catch (e) {
      return this.original(constraints)
    }
  },

  async startNativeStream (constraints) {
    if (this.stream) return this.stream
    const video = constraints.video && typeof constraints.video === 'object' ? constraints.video : {}
    this.facingMode = video.facingMode === 'user' ? 'user' : 'environment'
    const width = (video.width && (video.width.ideal || video.width)) || 640
    const height = (video.height && (video.height.ideal || video.height)) || 480
    const fps = (video.frameRate && (video.frameRate.ideal || video.frameRate)) || 15

    this.canvas = document.createElement('canvas')
    this.canvas.width = width
    this.canvas.height = height
    this.ctx = this.canvas.getContext('2d')
    this.ctx.fillStyle = '#000'
    this.ctx.fillRect(0, 0, width, height)

    const self = this
    this.frameListener = await PluginSecure.addListener('cameraFrame', (data) => { self.onFrame(data) })
    await PluginSecure.startCameraStream({ facingMode: this.facingMode, width, height, fps })

    this.canvasStream = this.canvas.captureStream(fps)
    this.stream = new MediaStream()
    const track = this.canvasStream.getVideoTracks()[0]

    track.getCapabilities = function () {
      return { torch: !!self.torchSupported, facingMode: [self.facingMode] }
    }
    track.applyConstraints = function (next) {
      const adv = next && next.advanced ? next.advanced[0] : next
      if (adv && typeof adv.torch === 'boolean' && hasMethod('setCameraTorch')) {
        PluginSecure.setCameraTorch({ enabled: adv.torch })
      }
      if (adv && adv.facingMode) {
        self.facingMode = adv.facingMode
        PluginSecure.startCameraStream({ facingMode: adv.facingMode, width: self.canvas.width, height: self.canvas.height, fps })
      }
      return Promise.resolve()
    }
    const originalStop = track.stop.bind(track)
    track.stop = function () {
      originalStop()
      self.dispose()
    }

    this.stream.addTrack(track)
    return this.stream
  },

  onFrame (data) {
    if (!this.ctx || !data || !data.base64) return
    const self = this
    const img = new Image()
    img.onload = function () {
      try { self.ctx.drawImage(img, 0, 0, self.canvas.width, self.canvas.height) } catch (e) { /* frame dropped */ }
    }
    img.src = 'data:image/jpeg;base64,' + data.base64
  },

  dispose () {
    try {
      if (this.frameListener && typeof this.frameListener.remove === 'function') this.frameListener.remove()
    } catch (e) { /* noop */ }
    this.frameListener = null
    if (hasMethod('stopCameraStream')) {
      try { PluginSecure.stopCameraStream() } catch (e) { /* noop */ }
    }
    try {
      if (this.canvasStream) this.canvasStream.getTracks().forEach((t) => t.stop())
    } catch (e) { /* noop */ }
    this.canvasStream = null
    this.stream = null
    this.canvas = null
    this.ctx = null
  }
}

export default ({ Vue }) => {
  if (Vue && Vue.prototype) {
    Vue.prototype.$pluginsecure = PluginSecureService
  }
  LocationGate.init()
  refreshCameraConfig().then(() => {
    CameraOverride.install()
    CameraStream.install()
  })
}
`
}

function installBootFile (quasarRoot) {
  const bootDir = path.join(quasarRoot, 'src', 'boot')
  if (!fs.existsSync(bootDir)) {
    warn(`${bootDir} not found, skipping boot file`)
    return
  }

  const bootPath = path.join(bootDir, BOOT_FILENAME)
  fs.writeFileSync(bootPath, bootFileSource())
  log(`wrote ${bootPath}`)

  const confName = ['quasar.conf.js', 'quasar.config.js']
    .find((name) => fs.existsSync(path.join(quasarRoot, name)))
  if (!confName) {
    warn('no Quasar config file found, register the boot file manually')
    return
  }

  const confPath = path.join(quasarRoot, confName)
  let conf = fs.readFileSync(confPath, 'utf-8')

  const bootArray = conf.match(/boot\s*:\s*\[([\s\S]*?)\]/)
  if (!bootArray) {
    warn(`could not find a "boot" array in ${confName}, register 'pluginsecure' manually`)
    return
  }
  if (/'pluginsecure'|"pluginsecure"/.test(bootArray[1])) {
    log(`'pluginsecure' already registered in ${confName}`)
    return
  }

  const entries = bootArray[1].trim()
  const indentMatch = bootArray[1].match(/\n(\s*)['"]/)
  const indent = indentMatch ? indentMatch[1] : '      '
  const comma = entries.length === 0 || /,\s*$/.test(bootArray[1]) ? '' : ','
  const chomped = bootArray[1].replace(/\s*$/, '')
  const injected = `${chomped}${comma}\n${indent}'pluginsecure'\n${indent.replace(/ {2}$/, '')}`

  conf = conf.replace(bootArray[0], `boot: [${injected}]`)
  fs.writeFileSync(confPath, conf)
  log(`registered 'pluginsecure' in ${confName}`)
}

// --- Entry -------------------------------------------------------------------

function runPostinstall () {
  if (SKIP) {
    log('PLUGINSECURE_SKIP_POSTINSTALL set, skipping')
    return
  }
  if (NESTED_INSTALL) {
    log('nested install detected, skipping postinstall')
    return
  }

  const initCwd = process.env.INIT_CWD || process.cwd()
  const quasarRoot = findQuasarRoot(initCwd)

  if (!quasarRoot) {
    log('not a Quasar project, nothing to do (run `npx pluginsecure-setup` from src-capacitor)')
    return
  }

  log(`Quasar project root: ${quasarRoot}`)
  installBootFile(quasarRoot)
}

if (require.main === module) {
  try {
    runPostinstall()
  } catch (error) {
    warn(`skipped automatic setup: ${error.message}`)
  }
}

module.exports = {
  findCapacitorDir,
  findQuasarRoot,
  ensureCapacitor3,
  syncNative,
  installBootFile,
  bootFileSource,
  log,
  warn
}
