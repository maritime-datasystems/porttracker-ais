# PortTracker AIS-catcher — Architecture

A headless Android AIS receiver for unattended port stations: a phone + RTL-SDR
dongle decodes AIS traffic and forwards it to PortTracker/TrustedDocks (MQTT),
local consumers (UDP/TCP), and a built-in web dashboard, with remote management
over an FRP tunnel and OTA self-updates.

- App code: `app/src/main/kotlin/com/porttracker/ais/` (~5.4k lines Kotlin)
- Native engine: vendored [AIS-catcher](https://github.com/jvde-github/AIS-catcher) +
  rtl-sdr/libusb/airspy under `app/src/main/jni/` (GPLv3), driven over JNI
- Feature specs: `docs/specs/`

```
                       ┌────────────────────────────────────────────────┐
 USB SDR ──fd──▶       │  AIScatcherNDK (C++, in-process)               │
 (RTL-SDR, AirSpy)     │  demod → AIS decode → NMEA + decoded JSON      │
                       │  ├── UDP outputs (×4)     ├── TCP listener     │
                       │  ├── Community sharing    └── internal web     │
                       │  └── JNI callback onNMEA(nmea)   (port 8888)   │
                       └───────┬───────────────────────────┬────────────┘
                               │ NMEA callbacks            │ HTTP (localhost)
                ┌──────────────▼─────────────┐   ┌─────────▼──────────────┐
                │ AisReceiverService          │   │ VesselCacheWriter      │
                │ (foreground, wake+wifi lock)│   │ polls /api/ships.json  │
                │  ├─ listener: MqttPublisher │   │ every 30 s → SQLite    │
                │  ├─ listener: SSE clients   │   └─────────┬──────────────┘
                │  └─ listener: NMEA ring buf │             │
                └──────────────┬─────────────┘   ┌─────────▼──────────────┐
                               │ manages         │ VesselDatabase (SQLite) │
   ┌───────────────────────────▼──────────────┐  │ vessel_static +         │
   │ AdminWebServer (NanoHTTPD, port 8080)    │  │ vessel_position (tiered)│
   │  /admin/* API · static web UI ·          │  └─────────────────────────┘
   │  reverse proxy → internal 8888           │
   └───────────────▲──────────────────────────┘
                   │ HTTP                ┌──────────────────────────┐
   LAN browser ────┘                     │ frpc (child process)     │
   In-app WebView ──── 127.0.0.1:8080    │ tunnel → FRP server      │
   Remote dashboard ── https://<station>.│ (subdomain per station)  │
                       tunnel domain ────▶──────────────────────────┘
```

## 1. Components

| Component | File | Role |
|---|---|---|
| `AisReceiverService` | `AisReceiverService.kt` | Foreground service; owns the native engine lifecycle, USB fd, wake/wifi locks, and starts/stops every subsystem. |
| `AisCatcherJava` | `com/jvdegithub/aiscatcher/AisCatcherJava.kt` | JNI bridge. Package/class name **must not change** — the native lib resolves callbacks by that exact name. |
| `AdminWebServer` | `AdminWebServer.kt` | NanoHTTPD on the public web port: admin API, static UI (filesDir override → APK assets), reverse proxy to the internal C++ server, NMEA SSE/polling, Basic-auth gate. |
| `MqttPublisher` | `MqttPublisher.kt` | Paho client publishing each NMEA sentence to the PortTracker broker (`aisc-json` wrapper or raw). Bounded queue survives reconnects. |
| `VesselCacheWriter` / `VesselDatabase` | `VesselCacheWriter.kt`, `VesselDatabase.kt` | 30 s poll of the engine's decoded ship list into a persistent SQLite cache + movement-gated position history with tiered retention. |
| `GpsForwarder` | `GpsForwarder.kt` | Phone GPS + compass → `$GPRMC/$GPGGA/$HEHDT` over UDP (for gpsd-style consumers). |
| `FrpTunnelManager` | `FrpTunnelManager.kt` | Runs the `frpc` binary (staged as `libfrpc.so`, see `scripts/fetch-frpc.sh`) as a child process; station name → public subdomain. |
| `UpdateManager` | `UpdateManager.kt` | OTA: fetches `update-manifest.json`, downloads APK, hands off to the Android installer. |
| `TrustedDocksApi` | `TrustedDocksApi.kt` | One-shot provisioning: API key → broker URL/credentials/user+antenna ids. |
| `ConfigurationManager` | `ConfigurationManager.kt` | SharedPreferences ⇄ JSON bridge for the web settings page (type-preserving writes). |
| `SettingsActivity` | `SettingsActivity.kt` | The only UI: a WebView on `127.0.0.1:<web port>` plus USB-permission plumbing and battery-exemption request. |
| `BootReceiver` / `UsbDeviceReceiver` / `ForceStartActivity` / `DebugReceiver` | — | Start paths: boot, USB attach, ADB (`am start -n …/.ForceStartActivity`). |
| `SupportedDevices` / `UsbDeviceScanner` | — | Single source of truth for supported VID/PID pairs; scan helper. |
| `InternalLog` | `InternalLog.kt` | 500-line in-memory ring log (tunnel status, generated credentials). Served at `GET /admin/api/logs`. |

## 2. Process & thread model

Everything runs in **one process**. Key threads:

- **main** — Activity/receivers; `onStartCommand` (enters foreground immediately,
  then scans/opens USB).
- **AIS-Receiver-Thread** — runs `runServiceLoop` → blocking native `Run()`.
  Native invokes `onNMEA` callbacks **on its own internal thread**; every
  listener must therefore be non-blocking (see §6).
- **MqttBatchTimer** — 1 s scheduled flush of the NMEA queue to the broker.
- **VesselCacheWriter** — 30 s scheduled poll (daemon), plus a transient
  `PositionMaintenance` thread at most once/day.
- **NanoHTTPD worker threads** — one per HTTP request; SSE clients each get a
  dedicated `NmeaSseWriter` thread fed by a bounded queue.
- **frpc** — a separate OS child process (not a thread), plus one reader thread
  draining its stdout into `InternalLog`.

### Native engine constraints (hard-won)

- `Run()` blocks until `forceStop()`/`Close()`; it must **only run with a valid
  USB fd** — calling it without one SIGSEGVs.
- Re-initialising in-process is fragile; the service serializes all native
  execution behind a static `serviceLock` and sleeps ~500 ms around `Close()`
  to let the internal web port free up.
- Without a device, the service stays in a **web-only keep-alive loop** so the
  dashboard, tunnel, and MQTT status stay reachable; plugging in an SDR (or
  `POST /admin/api/engine/restart`) triggers an in-process engine restart.

## 3. Network ports

| Port | Bound | What |
|---|---|---|
| `pref_local_web_port` (default **8080**) | all interfaces | `AdminWebServer` — the only intended entry point (UI, API, proxy). |
| **8888** (`INTERNAL_WEB_PORT`) | 127.0.0.1 | Native AIS-catcher web server (map UI, `/api/ships.json`, stats). Only reached via the proxy or localhost. |
| `tcp_port` (default 10111) | all | Optional raw NMEA TCP listener (native). |
| `udp1..4` | outbound | Optional NMEA/JSON UDP outputs (native). |
| `gpsd_port` (default 2947) | outbound UDP | GPS forwarder target. |
| 7000 → FRP server | outbound | frpc control connection (`FRP_SERVER_ADDR` in `FrpTunnelManager`). |

## 4. HTTP API (AdminWebServer)

Routing order: auth check → `/admin/*` handlers → NMEA endpoints → proxy
(`/api/*`, `/metrics`, `/stat.json`, `/tiles/`, `/cdn/`) → static files
(`filesDir/web` override, then APK `assets/web`) → proxy fallback.

| Endpoint | Method | Purpose |
|---|---|---|
| `/admin/status` | GET | liveness stub |
| `/admin/api/config` | GET/POST | full SharedPreferences dump / type-preserving update; POST triggers `applyLiveConfigChanges()` (FRP + MQTT + DB writer restart, no engine restart) |
| `/admin/api/status_ui` | GET | dashboard status block (IP, service, device, GPS) |
| `/admin/api/status` | GET | SDR/USB status incl. device list + permission state |
| `/admin/api/health` | GET | **unauthenticated** monitoring endpoint (remote dashboard polls through the tunnel); intentionally excludes GPS coords and credentials |
| `/admin/api/logs` | GET | `InternalLog` dump (incl. auto-generated remote credentials) |
| `/admin/api/service/start` · `/service/stop` | POST | start service / stop SDR engine (web server stays up) |
| `/admin/api/engine/restart` | POST | re-scan USB + restart native engine in-process |
| `/admin/api/mqtt/restart` · `/frp/restart` | POST | restart publisher / tunnel from current prefs |
| `/admin/api/usb/request_permission` | POST | fire the Android USB permission dialog |
| `/admin/api/gateway/connect` | POST | `{api_key}` → TrustedDocks provisioning; persists broker/creds/topics (`ais/{fmt}/{userId}/{antennaId}`) |
| `/admin/api/gateway/status` | GET | MQTT connection + station info |
| `/admin/api/update/check` · `/update/install` · `/update/status` | GET/POST | OTA flow |
| `/admin/api/vessels` (`?limit&offset&q`) · `/vessels/count` · `/vessel?mmsi=` · `/vessels.csv` | GET | vessel cache reads |
| `/admin/api/vessel/track?mmsi=&from=&to=&res=&limit=` | GET | position history |
| `/admin/api/positions/stats` · `/positions/maintenance` | GET/POST | history stats / manual downsampling run |
| `/api/nmea_buffer?since=<seq>` | GET | polling feed of recent NMEA (ring of 200, returns lines with seq > since) |
| `/api/sse` (alias `/admin/api/nmea_stream`) | GET | live NMEA as Server-Sent Events |

Proxy notes: request **bodies are not forwarded** (GET-style endpoints only);
SSE responses from the internal server get a heartbeat-injecting relay.

## 5. Security model

- **Local/LAN**: open by default; Basic auth enforced only when
  `web_auth_enabled` is set (loopback WebView is always allowed).
- **Remote (FRP tunnel)**: always requires Basic auth, fail-closed. Remote is
  detected via `X-Forwarded-For` or a DNS-name `Host` header
  (`isRemoteRequest`). If remote access is enabled with no credentials, the
  service **generates** a password, enables auth, and writes the credentials to
  `InternalLog` (readable locally at `/admin/api/logs`).
- Static file serving is containment-checked against the web root and rejects
  `..` segments (filesDir holds `shared_prefs` with broker credentials).
- `/admin/api/health` is deliberately unauthenticated and must never expose
  location or credential material.
- Known accepted risks: HTTP (not HTTPS) on the LAN; credentials in
  SharedPreferences (app-private); a default FRP auth token in code
  (override with the `frp_auth_token` pref); OTA APK integrity relies on
  Android's signature check at install time (manifest is fetched over HTTPS).

## 6. NMEA fan-out rules

`AisCatcherJava.onNMEA` → `AisReceiverService.onNMEA` runs on the **native
receiver thread**. Rules for listeners (`CopyOnWriteArrayList`):

1. Never block: hand off to a queue/executor. SSE clients use a bounded
   512-entry queue with drop-on-overflow; MQTT uses a bounded 2000-entry queue
   flushed by its own timer thread.
2. Never log per-sentence on this path (busy ports decode dozens/second).
3. Listeners registered by subsystems must be removed symmetrically on stop
   (service `onDestroy`, `AdminWebServer.stop`, MQTT stop).

## 7. Vessel cache & position history (SQLite)

DB `vessel_cache.db`, WAL mode, singleton `VesselDatabase`.

- `vessel_static` — one row per **MMSI** (IMO is a nullable indexed attribute;
  see `docs/specs/internal-vessel-cache.md` for why). Merge-upsert keeps known
  values when a frame omits them; `msg_count` counts *polls seen*, not
  messages.
- `vessel_position` — PK `(mmsi, ts)`, `resolution` 0=raw / 1=hourly / 2=daily.
  Ingestion is movement-gated (≥50 m move, ≥15° turn, or 5 min heartbeat).
  Daily maintenance downsamples: raw >90 d → last-point-per-hour; hourly >1 y →
  last-point-per-day (`runMaintenance`, no window functions — minSdk 23).
- Feeder: `VesselCacheWriter` polls `http://127.0.0.1:8888/api/ships.json`
  (already-decoded JSON; **no AIS bit-parsing in Kotlin**). Enabled by the
  `internal_db_enabled` pref; positions additionally gated by
  `position_logging_enabled`.

## 8. Service lifecycle

Start paths: launcher Activity (auto web-only + SDR if present) · boot
(`auto_start_boot`) · USB attach (`auto_start_usb`) · ADB
(`ForceStartActivity`, `DebugReceiver`) · remote API (`/service/start`).

- `onStartCommand` enters foreground **immediately** (ANR-safe), then resolves
  the USB device (intent extras → scan fallback) and spawns the receiver
  thread. Returns `START_STICKY` — after a system kill the service restarts
  and re-scans (null intent → scan fallback path).
- Engine restart matrix (all funnel through `restartRequested` +
  `shouldStop` + thread interrupt):
  - USB attached while web-only → open fd → in-process engine restart
  - `POST /admin/api/engine/restart` → `forceStop()` → re-run with fresh prefs
  - engine exits unexpectedly → web-only keep-alive loop (notification
    "SDR disconnected"), waiting for a restart trigger
- `applyLiveConfigChanges()` (after web config save) restarts FRP/MQTT/DB
  writer only — native engine settings (device type, UDP/TCP outputs, web
  port) need an engine or app restart.

## 9. Configuration keys (SharedPreferences)

| Area | Keys |
|---|---|
| Device | `device_type` (ordinal into `DeviceType`) |
| UDP out | `udp{1..4}_enabled/_host/_port/_json` |
| TCP out | `tcp_enabled`, `tcp_port` |
| Web | `webviewer_enabled`, `pref_local_web_port`, `web_auth_enabled`, `web_auth_username`, `web_auth_password` |
| GPS | `gpsd_enabled`, `gpsd_host`, `gpsd_port`, `gpsd_interval` |
| Hub | `hub_sharing`, `hub_key` (aiscatcher.org community sharing) |
| Remote | `pref_enable_remote`, `pref_station_name`, `frp_auth_token` |
| MQTT | `mqtt_enabled`, `mqtt_broker_url`, `mqtt_username`, `mqtt_password`, `mqtt_topic_raw`, `mqtt_topic_json`, `mqtt_format` (`aisc-json`\|`raw`), `mqtt_api_key`, `mqtt_user_id`, `mqtt_antenna_id`, `mqtt_port_id`, `mqtt_station_name`, `mqtt_user_name`, `mqtt_company_name` |
| Vessel DB | `internal_db_enabled`, `position_logging_enabled`, `last_position_maintenance_ms` |
| Auto-start | `auto_start_boot`, `auto_start_usb`, `auto_start_launch` |
| OTA | `update_manifest_url` |

Note: values written via the web config page arrive as strings; readers use
`ServiceConfig.fromPreferences` (string-tolerant) or tolerant `.all[key]`
reads for booleans that may have been stored as `"true"`.

## 10. Build, release, OTA

- Build: `./gradlew assembleDebug` — fully self-contained (native sources
  vendored; NDK/CMake via Gradle). frpc is optional (`scripts/fetch-frpc.sh`).
- `versionName` embeds the build timestamp (`3.0-MMdd-HHmm`); web assets are
  re-extracted to `filesDir/web` whenever it changes. Bump `versionCode` in
  `app/build.gradle.kts` for every OTA release.
- OTA: publish the APK (GitHub Releases), update `update-manifest.json`
  (`version_code`, `apk_url`, …); stations check via
  `/admin/api/update/check` and install via `/admin/api/update/install`
  (user confirms the installer dialog on-device unless the device grants
  unattended install rights).

## 11. Known limitations

- `$HEHDT` from `GpsForwarder` is **magnetic** compass heading, unlabeled as
  such (no declination correction), and assumes phone orientation.
- The admin proxy does not forward POST bodies to the internal server.
- One SDR device per station; first supported device found wins.
- MQTT QoS-1 with bounded queues: sustained broker outages > queue capacity
  drop the oldest sentences (by design — freshness beats completeness here).
- The web config save path may persist new keys as strings (see §9).
