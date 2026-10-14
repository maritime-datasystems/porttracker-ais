# PortTracker AIS release audit — 14 October 2026

## Verdict and scope

**Not ready for public store release.** Reviewed the Android Kotlin app, JNI integration, web admin routing/authentication, build/manifest configuration, network output, updater, and selected persistence paths. This is a targeted source and build review, not an exhaustive audit of every vendored C/C++ dependency or a completed hardware soak test.

The repository is Android-only. The store assessment below assumes Google Play; Apple App Store distribution requires a separate iOS implementation and a review of hardware/background-operation feasibility.

GitHub's latest release is [v30](https://github.com/maritime-datasystems/porttracker-ais/releases/tag/v30), published 28 July 2026, with a 29,532,076-byte APK. Downloaded and inspected that exact artifact: package `com.porttracker.ais`, versionCode 30, targetSdk 34, Android Debug signing certificate. A connected Android 16 device also has v30 installed; it was not upgraded or restarted during this review.

## Fixes applied in this working tree

- **Internal web server exposed on the LAN:** Kotlin passed a loopback address but JNI did not accept/use that parameter. JNI now applies the supplied binding; the secondary native viewer also binds loopback. Previously the internal API could bypass the authenticated admin proxy.
- **Native callback batches treated as single sentences:** split newline-separated batches before notifying MQTT, NMEA output, and web listeners. Empty batches no longer increment traffic counters; tag blocks and multipart boundaries are preserved. Added three regression tests.
- **Native memory/concurrency defects:** release five temporary JNI string references after callbacks and old class global references during reinitialization. Make the stop flag atomic. Protect NMEA buffer writes/draining with a mutex; reset the per-message accumulator so a multi-message native delivery does not duplicate preceding sentences.
- **Android 6–7 service startup:** replace direct API-26 `startForegroundService` calls in SettingsActivity with ContextCompat. Guard API-26 process termination calls and annotate the API-26-only WebView callback.
- **USB service eligibility:** only claim connectedDevice when a supported device has USB permission, rather than for any attached USB device.
- **Broadcast registrations:** use ContextCompat with explicit non-exported flags across supported Android versions.
- **Phone location permissions:** check permissions at the location registration site, rather than relying only on cached state.
- **Unreachable API aliases:** route `/admin/api/nmea_buffer` and `/admin/api/nmea_stream` before the catch-all `/admin/` handler.
- **FRP startup failure:** clear the running flag when binary/config preparation fails, allowing retries. Concurrent start/stop ownership still needs serialization.
- **TCP client race:** register clients before starting writer threads, and acquire the output stream inside the cleanup-protected block.
- **WebView origin checks:** require the configured local HTTP port and exact local host; remove the geolocation hostname-prefix check that also accepted lookalike hostnames.

The existing GitHub updater and release manifest remain unchanged. No release was published and no signing keys were created.

## Remaining high-priority runtime/security findings

| Priority | Finding and evidence | Required follow-up |
|---|---|---|
| P1 | `AisReceiverService.runServiceLoop` permits a first run plus a limited number of reruns rather than a persistent state loop. `triggerEngineRestart` sets a flag and calls native forceStop, but does not wake the web-only sleep loop consistently. `onDestroy` waits up to five seconds on the main thread; delayed starts and MQTT workers can outlive the service. | Refactor into one serialized start/stop/reconfigure controller. Test repeated attach/detach/restart, rapid settings saves, and shutdown during MQTT connect. |
| P1 | JNI starts global `server` in `InitNative` and also creates `webviewer` for the same internal port. Both receive model connections; the TCP implementation enables SO_REUSEPORT. Global server shutdown is absent from `Close`. | Consolidate to one native HTTP server with clear lifetime. Test repeated receiver creation and verify requests never reach stale state. Loopback binding is fixed, but duplicate ownership is not. |
| P1 | `WebAuth` still ships `admin/admin`. LAN users can access full preferences and logs using publicly known credentials. Empty configured credentials also allow local/LAN access. `FrpTunnelManager.getAuthToken` contains a shared fallback token. | Generate per-install admin credentials with a native/local recovery path; fail closed when auth is enabled. Replace the shared tunnel token with provisioned station credentials and rotate the server credential. Do not merely remove the fallback without coordinating existing stations. |
| P1 | Auth bypass trusts loopback plus HTTP Host/forwarded-header classification. Any local app can reach the unauthenticated loopback API. No explicit origin/CSRF defense on mutating endpoints. | Use an app-specific authenticated local channel; validate request origins for browser mutations and define a trusted tunnel boundary. Do not treat Host as proof of requester identity. |
| P1 | `MqttPublisher.start` installs flush/status timers only after initial connect succeeds. Initial offline/failure leaves no functional publishing timers. `flushBatch` drains the queue before publishing and drops failures (including in-flight saturation). Service startup can race stop/restart and register obsolete listeners. | Explicit initial-connect retries, bounded retry of unsent messages, lifecycle generation checks, and a broker-outage/burst regression test. |
| P1 | `runNativeEngine` rejects every `fd < 0`, including RTLTCP and SpyServer sources; network source host/port settings are not wired through the service configuration. | Either implement network source configuration/startup or stop advertising these modes. |
| P1 | `auto_start_launch=false` passes USB IDs 0/0, but service startup scans USB again and can start the receiver anyway. | Pass an explicit web-only/receiver-enabled intent option and respect it during scanning and sticky restart. |
| P2 | Config POST accepts arbitrary preference keys/types and out-of-range ports. It returns `restart_required:false` although SDR/UDP/web-port settings are not all applied live. | Typed allowlist/schema, validation before atomic save, and accurate per-setting restart information. Invalid ports can strand the UI. |
| P2 | `AisDecoder` accepts truncated payloads by padding zeros, ignores fill bits/checksum, allocates fragment arrays from unchecked counts, and can combine stale fragments sharing a sequence/channel. | Validate lengths/counts/checksums and bound/time-expire fragment state before NMEA 2000 conversion. |
| P2 | NMEA output reports active even when all enabled transports fail. GPS GGA hardcodes satellite count 8, HDOP 1.0 and geoid separation 0; unavailable altitude becomes 0. | Report actual transport readiness and unknown sensor fields honestly; verify with a chartplotter. |
| P2 | `BootReceiver` requests `USB_PERMISSION_BOOT` without a corresponding result handler; the immutable pending intent cannot carry normal fill-in extras. | Route results to the service's permission handler using a package-scoped pending intent and handle boot restrictions. |
| P2 | APK download has no app-side expected package/version/digest validation, permits configurable HTTP sources, and concurrent downloads can write the same file. | Require HTTPS, serialize download/install, validate artifact identity/version, and use an atomic temporary file. Android signature validation remains a separate installation safeguard. |
| P2 | `allowBackup=true` with no exclusions can back up station identity and credentials; restore may clone station identity. | Define backup/data-transfer exclusions and a deliberate reprovisioning policy. |
| P2 | Launcher icons exist only in `mipmap-anydpi-v26`, despite minSdk 23. | Add legacy launcher resources and verify Android 6/7 installation and launcher display. |

Additional maintenance: position history records polling time rather than observation time and can retain repeated stale positions; `formatEta` turns unknown hour/minute values into midnight. Health `uptime_ms` is device uptime, not service uptime. These should be clarified before making data-quality claims.

## Store release blockers

1. **Target/API migration:** targetSdk is 34. Google's current requirement for new apps/updates is API 36. Upgrade the supported AGP/Gradle/SDK toolchain and test behavior changes, rather than only changing a number. [Official target API requirements](https://developer.android.com/google/play/requirements/target-sdk).
2. **Foreground-service behavior:** web-only/network mode falls back to dataSync; there is no `onTimeout` handler. Targeting newer Android activates dataSync limits and boot-start restrictions. Location while-in-use rules also affect boot/sticky starts. The fallback to two-argument startForeground uses manifest types, not a safe no-type escape. Define the service model for USB, network reception, and UI-only operation. [Foreground-service changes](https://developer.android.com/develop/background-work/services/fgs/changes), [timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).
3. **Separate distribution behavior:** preserve GitHub sideload updates, but create a Play variant that omits APK self-update UI/endpoints and `REQUEST_INSTALL_PACKAGES`. Google's permission policy excludes this use for normal self-updates. [Permission policy](https://support.google.com/googleplay/android-developer/answer/16558241).
4. **Signing and migration:** v30 is debug-signed. A new production signing certificate cannot transparently replace it under the same package. Decide whether existing stations remain on the sideload channel, use a distinct store package, or explicitly migrate/reinstall with settings export. Configure secure upload signing and Play App Signing; do not commit keys.
5. **Native packaging:** The project already supplies `-Wl,-z,max-page-size=16384`; AGP 8.2/NDK 25 still need packaging and full artifact verification. The downloaded v30 arm64 AIS library has 0x4000 ELF alignment and FRP has 0x10000; that alone does not certify a newly built AAB or runtime compatibility. Verify every shipped ABI/prebuilt, generated APK alignment, and a 16 KB device/emulator. FRP is absent for x86 even though the app includes that ABI. [Official page-size guidance](https://developer.android.com/guide/practices/page-sizes).
6. **Privacy/store disclosures:** no privacy or account-deletion entry point was found in the web settings UI. Prepare an in-app policy link and Data safety disclosures for GPS, station identity, account provisioning, MQTT sharing and third-party services. If the linked sign-in flow allows account creation, provide deletion inside/outside the app. Review foreground-service and battery-exemption declarations. [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).
7. **Release hygiene:** remove debug-only exported ForceStartActivity from the store manifest; use deterministic release version names and increasing version codes; supply store graphics, support details and reviewer instructions for SDR hardware. Confirm GPLv3/native dependency notices and corresponding source distribution obligations using the licenses bundled in this repository.

## Dead code and maintainability

Lint and source searches identify the old native preference XML screens (`preferences*.xml`), `activity_settings.xml`, device-type arrays and GPS interval arrays as unused after migration to the programmatic WebView UI. Remove together after confirming no external packaging workflow consumes them. They currently duplicate the web settings model.

`DebugReceiver` is non-exported although its comment advertises an adb broadcast command; the exported force-start activity provides a separate debug path. Consolidate diagnostic components into a debug source set. Several ServiceConfig MQTT fields duplicate direct preference reads. Broad keep rules retain all app fields and all Kotlin INSTANCE fields; narrow them only with a tested minified-release JNI/Gson contract. Avoid sweeping vendored upstream files as "dead" merely because another platform uses them.

## Validation

- `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease`: passed. Both debug and minified, unsigned release APKs built for arm64-v8a, armeabi-v7a, x86 and x86_64.
- Lint: **0 errors, 26 warnings** (initial run: 17 errors). The connectedDevice manifest lint diagnostic is narrowly suppressed with an explanatory comment because this AGP version does not account for USB permission; runtime eligibility now checks USB permission. The WebView renderer callback carries an API annotation because Android only invokes it on API 26+.
- JUnit: **3 tests, 0 failures**, covering native batch boundaries, blank input, and preservation of tag blocks/multipart sentences. This does not test the entire decoder.
- `node --check`: passed for settings_renderer.js and extracted inline index.html scripts.
- `git -c core.whitespace=cr-at-eol diff --check`: passed (JNI file preserves existing CRLF).
- A stale native build cache referenced the repository's former location. Moved generated `app/.cxx` aside under `/tmp/porttracker-ais-cxx-before-audit-*`, then rebuilt successfully.
- Existing APK signing and arm64 ELF alignment inspected read-only; no device installation, service restart, GitHub upload or store submission performed.

Remaining build warnings include unused resources, old dependencies/target API, localization, and legacy JNI packaging configuration. Move `extractNativeLibs` to Gradle `packaging.jniLibs.useLegacyPackaging = true` as part of the toolchain migration, preserving FRP's need for an extracted executable.

Suggested next implementation order: (1) native/service lifecycle and MQTT recovery, (2) credential provisioning and config validation, (3) separate Play/sideload variants plus signing migration and API 36 changes, (4) hardware/emulator regression matrix and internal Play testing. Hardware reception, live tunnel/broker recovery, navigation-app interoperability and a long-running 16 KB-device test remain necessary; a successful build is not release approval.

## Follow-up: lifecycle and MQTT fixes (14 October 2026, evening)

Addresses the first and fifth P1 rows above and part of the second.

- **Service lifecycle.** `runServiceLoop` is now one loop of engine cycles until stop: every cycle re-reads config, re-scans USB and opens a fresh fd on the receiver thread, which alone owns the USB connection. Restart requests (web UI, dongle attach, permission grant) and stop requests go through `requestEngineRestart` / `requestServiceStop` (flag + native stop + monitor wake-up) instead of `shouldStop` + `interrupt()`. Previously a station that lost its dongle twice, or got two restart requests, stayed web-only for good, and a stale fd from an unplugged dongle could be handed back to the engine. An engine that keeps dying right after start backs off (5 s doubling to 2 min). `onDestroy` waits at most 1.5 s on the main thread; teardown it does not wait for is finished by the receiver thread, and a new service instance waits on the static `serviceLock`.
- **Lost native stop.** `Run()` reset the native stop flag after starting the device, so a stop requested while the engine was starting up was silently discarded. The flag is now reset in `InitNative`, and Kotlin skips `Run()` if a request arrived before that.
- **Internal server started once.** `InitNative` started the global `WebViewer` again on every engine cycle, adding duplicate sinks and another listener on the same port. It now starts once per process. The secondary per-receiver `webviewer` on the same port still exists (rest of that P1 row).
- **MQTT recovery.** `MqttPublisher.start` no longer blocks on the first connect: timers start immediately, sentences queue while offline, and a failed first connect is retried with backoff (5 s doubling to 5 min) until Paho's automatic reconnect takes over. `flushBatch` keeps a refused sentence (not connected, in-flight window full, link dropping) for the next flush instead of discarding the drained batch. Callbacks from a replaced client are ignored, and `isConnected` reads the active publisher only. MQTT start/stop in the service is serialized on one executor, so overlapping config saves and sign-ins can no longer leave an orphaned publisher registered as a listener, and `onDestroy` no longer disconnects on the main thread.

Verified: both flavors build (including native), unit tests and lint pass (0 errors). The MQTT changes were exercised in a temporary JVM test against a real Mosquitto broker in Docker (not committed): broker down at start → backlog of 50 delivered once it came up; burst of 1500 delivered completely; broker restart → 100 sentences queued during the outage delivered after reconnect. The same test against the previous `MqttPublisher` failed at the first step (never connected). - **Receiver stop/start from the web UI.** "Stop" only stops the engine (the service and web UI stay up); with the new loop that would have been treated as an unexpected exit and undone after 5 s. It is now an explicit paused state (`stopReceiver` / `startReceiver`). "Start" previously did nothing while the service was running, so a receiver stopped from the web UI never came back without restarting the app.

**USB phone test** (Samsung SM-S938B, Android 16, sideload debug build, no SDR attached):

| Check | Result |
|---|---|
| Install over 3.1.0, cold launch, foreground service | Pass |
| 5 restarts 0.3 s apart | Coalesced into one new cycle, back to web-only |
| 4 restarts 2.5 s apart | 4 cycles; `/api/nmea_buffer`, `/admin/api/nmea_buffer`, `status_ui`, `doctor` all 200 afterwards |
| MQTT with broker down at start (Mosquitto on the dev machine via `adb reverse`) | Retried after 5 s and 10 s, connected by itself once the broker came up |
| Broker restart | Reconnected within ~1.3 s |
| 3 config saves 0.2 s apart | Two in-flight connects closed on arrival; exactly one session left at the broker |
| `kill -9` of the app process | Service restarted by Android (START_STICKY) after ~4 s, MQTT reconnected |
| Web UI receiver stop → start | Stays stopped while paused; start runs a new engine cycle |
| Crashes / ANRs | None in the crash buffer or logcat |

The phone's MQTT settings were set back to disabled afterwards. Still untested: anything with an SDR attached (reception, unplug/replug while running, stop/start with a live engine), and a clean `onDestroy` — the service is not exported, so it could not be stopped from adb.
