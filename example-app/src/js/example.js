import { WahanaPlugin } from 'capacitor-plugingps-wahana';

let currentWatchId = null;
let currentStatus = {
  enabled: true,
  granted: true,
  isMock: false,
  usable: true,
};

function logMessage(msg) {
  const logBox = document.getElementById('logBox');
  if (!logBox) return;
  const time = new Date().toLocaleTimeString();
  logBox.innerText = `[${time}] ${msg}\n` + logBox.innerText;
}

function updateStatusUI(status) {
  currentStatus = status;

  // 1. Usable badge
  const badgeUsable = document.getElementById('badgeUsable');
  if (badgeUsable) {
    if (status.usable) {
      badgeUsable.className = 'badge badge-success';
      badgeUsable.innerText = 'USABLE (AMAN)';
    } else {
      badgeUsable.className = 'badge badge-danger';
      badgeUsable.innerText = 'BLOCKED (TERKUNCI)';
    }
  }

  // 2. GPS Badge
  const badgeGps = document.getElementById('badgeGps');
  if (badgeGps) {
    badgeGps.className = status.enabled ? 'badge badge-success' : 'badge badge-danger';
    badgeGps.innerText = status.enabled ? 'GPS: AKTIF' : 'GPS: NONAKTIF';
  }

  // 3. Permission Badge
  const badgePerm = document.getElementById('badgePerm');
  if (badgePerm) {
    badgePerm.className = status.granted ? 'badge badge-success' : 'badge badge-danger';
    badgePerm.innerText = status.granted ? 'IZIN: DIBERIKAN' : 'IZIN: DITOLAK';
  }

  // 4. Mock Badge
  const badgeMock = document.getElementById('badgeMock');
  if (badgeMock) {
    badgeMock.className = status.isMock ? 'badge badge-danger' : 'badge badge-success';
    badgeMock.innerText = status.isMock ? 'FAKE GPS: TERDETEKSI' : 'FAKE GPS: TIDAK';
  }

  // 5. Gating Overlay Blocker
  const overlay = document.getElementById('gatingOverlay');
  const btnFixGps = document.getElementById('btnFixGps');
  const btnFixPerm = document.getElementById('btnFixPerm');
  const gatingIcon = document.getElementById('gatingIcon');
  const gatingTitle = document.getElementById('gatingTitle');
  const gatingDesc = document.getElementById('gatingDesc');

  if (overlay) {
    if (!status.usable) {
      overlay.style.display = 'flex';

      if (status.isMock) {
        gatingIcon.innerText = '🛡️';
        gatingTitle.innerText = 'Manipulasi Lokasi Terdeteksi!';
        gatingDesc.innerText =
          'Aplikasi Fake GPS / Mock Location terdeteksi aktif. Matikan aplikasi pemalsu lokasi untuk menggunakan aplikasi ini.';
        if (btnFixGps) btnFixGps.style.display = 'none';
        if (btnFixPerm) btnFixPerm.style.display = 'none';
      } else if (!status.enabled) {
        gatingIcon.innerText = '📍';
        gatingTitle.innerText = 'GPS Perangkat Nonaktif';
        gatingDesc.innerText =
          'Layanan lokasi (GPS) pada perangkat Anda sedang mati. Nyalakan GPS untuk membuka blokir aplikasi.';
        if (btnFixGps) btnFixGps.style.display = 'block';
        if (btnFixPerm) btnFixPerm.style.display = 'none';
      } else if (!status.granted) {
        gatingIcon.innerText = '🔒';
        gatingTitle.innerText = 'Izin Lokasi Belum Diberikan';
        gatingDesc.innerText =
          'Aplikasi Wahana SWB membutuhkan izin akses lokasi presisi untuk mencatat pengiriman paket.';
        if (btnFixGps) btnFixGps.style.display = 'none';
        if (btnFixPerm) btnFixPerm.style.display = 'block';
      }
    } else {
      overlay.style.display = 'none';
    }
  }
}

function updateCoordUI(pos) {
  if (!pos || !pos.coords) return;
  const c = pos.coords;

  const setVal = (id, val) => {
    const el = document.getElementById(id);
    if (el) el.innerText = val !== null && val !== undefined ? val : '-';
  };

  setVal('valLat', c.latitude.toFixed(6));
  setVal('valLng', c.longitude.toFixed(6));
  setVal('valAcc', `${c.accuracy.toFixed(1)} m`);
  setVal('valSpeed', c.speed !== null ? `${c.speed.toFixed(1)} m/s` : '-');
  setVal('valIsMock', c.isMock ? '🔴 TERDETEKSI' : '🟢 ASLI (VALID)');
  setVal('valTime', new Date(pos.timestamp).toLocaleTimeString());
}

// Initial checks & setup
async function init() {
  logMessage('Menginisialisasi WahanaPlugin...');

  // Setup listeners
  try {
    await WahanaPlugin.addListener('locationStatusChanged', (status) => {
      logMessage(`[Event] Status berubah: enabled=${status.enabled}, granted=${status.granted}, mock=${status.isMock}, usable=${status.usable}`);
      updateStatusUI(status);
    });

    await WahanaPlugin.addListener('location', (pos) => {
      logMessage(`[Event] Posisi baru: ${pos.coords.latitude.toFixed(4)}, ${pos.coords.longitude.toFixed(4)} (mock: ${pos.coords.isMock})`);
      updateCoordUI(pos);
    });

    logMessage('Menjalankan enforceLocation otomatis saat startup...');
    const initialStatus = await WahanaPlugin.enforceLocation();
    logMessage(`Status awal: usable=${initialStatus.usable}`);
    updateStatusUI(initialStatus);
  } catch (err) {
    logMessage(`Gagal inisialisasi: ${err.message || err}`);
  }

  // App resume check
  document.addEventListener('resume', async () => {
    logMessage('[App Resume] Memeriksa ulang status lokasi...');
    try {
      const status = await WahanaPlugin.checkLocationStatus();
      updateStatusUI(status);
    } catch (e) {
      logMessage(`Error resume check: ${e.message}`);
    }
  });
}

// Global button handlers
window.handleGetCurrentPosition = async () => {
  logMessage('Mengambil posisi saat ini...');
  try {
    const pos = await WahanaPlugin.getCurrentPosition({ enableHighAccuracy: true });
    updateCoordUI(pos);
    logMessage(`Posisi didapat: Lat ${pos.coords.latitude.toFixed(5)}, Lng ${pos.coords.longitude.toFixed(5)}, Mock: ${pos.coords.isMock}`);
  } catch (e) {
    logMessage(`Error getCurrentPosition: ${e.message || e}`);
  }
};

window.handleToggleWatch = async () => {
  const btn = document.getElementById('btnWatch');
  const statusText = document.getElementById('watchStatusText');

  if (currentWatchId) {
    logMessage(`Menghentikan watch ${currentWatchId}...`);
    try {
      await WahanaPlugin.stopWatch({ id: currentWatchId });
      currentWatchId = null;
      if (btn) {
        btn.innerText = 'Mulai Watch (Realtime)';
        btn.className = '';
      }
      if (statusText) statusText.innerText = 'Idle';
      logMessage('Watch dihentikan.');
    } catch (e) {
      logMessage(`Gagal stop watch: ${e.message}`);
    }
  } else {
    logMessage('Memulai watch real-time (interval 2000ms)...');
    try {
      const res = await WahanaPlugin.watchPosition(
        {
          enableHighAccuracy: true,
          interval: 2000,
          fastestInterval: 1000,
        },
        (pos, err) => {
          if (err) {
            logMessage(`Watch callback error: ${err.message || err}`);
            return;
          }
          if (pos) {
            updateCoordUI(pos);
          }
        }
      );
      currentWatchId = res.id;
      if (btn) {
        btn.innerText = 'Hentikan Watch';
        btn.className = 'btn-danger';
      }
      if (statusText) statusText.innerText = '🔴 Streaming Aktif';
      logMessage(`Watch aktif dengan ID: ${res.id}`);
    } catch (e) {
      logMessage(`Gagal watchPosition: ${e.message || e}`);
    }
  }
};

window.handleCheckStatus = async () => {
  logMessage('Mengecek status lokasi...');
  try {
    const status = await WahanaPlugin.checkLocationStatus();
    logMessage(`Hasil status: ${JSON.stringify(status)}`);
    updateStatusUI(status);
  } catch (e) {
    logMessage(`Error checkLocationStatus: ${e.message}`);
  }
};

window.handleEnforceLocation = async () => {
  logMessage('Menjalankan enforceLocation (Native Gating Blocker)...');
  try {
    const status = await WahanaPlugin.enforceLocation();
    logMessage(`Hasil enforceLocation: ${JSON.stringify(status)}`);
    updateStatusUI(status);
  } catch (e) {
    logMessage(`Error enforceLocation: ${e.message}`);
  }
};

window.handleOpenLocationSettings = async () => {
  logMessage('Membuka pengaturan GPS...');
  try {
    await WahanaPlugin.openLocationSettings();
  } catch (e) {
    logMessage(`Error openLocationSettings: ${e.message}`);
  }
};

window.handleOpenAppSettings = async () => {
  logMessage('Membuka pengaturan aplikasi...');
  try {
    await WahanaPlugin.openAppSettings();
  } catch (e) {
    logMessage(`Error openAppSettings: ${e.message}`);
  }
};

window.handleRequestPermissions = async () => {
  logMessage('Meminta izin lokasi...');
  try {
    const res = await WahanaPlugin.requestPermissions();
    logMessage(`Hasil perizinan: ${JSON.stringify(res)}`);
    const status = await WahanaPlugin.checkLocationStatus();
    updateStatusUI(status);
  } catch (e) {
    logMessage(`Error requestPermissions: ${e.message}`);
  }
};

window.clearLogs = () => {
  const logBox = document.getElementById('logBox');
  if (logBox) logBox.innerText = '';
};

init();
