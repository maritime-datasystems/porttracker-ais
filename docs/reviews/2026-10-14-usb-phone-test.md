# USB phone smoke test — 14 October 2026

Test device: Samsung SM-S938B, Android 16/API 36, connected by USB ADB. PortTracker was absent at the beginning of this test. Installed the published GitHub v30 APK, then updated in place to the locally patched debug build (versionCode 30, versionName 3.0-1014-1542). App data was preserved during the upgrade.

| Check | Published v30 | Patched build |
|---|---|---|
| Installation and cold launch | Pass | Pass |
| Web UI and status endpoint | HTTP 200 | HTTP 200; rendered status visually verified |
| Foreground service without SDR | Running, dataSync | Running, dataSync |
| `/api/nmea_buffer` | 200 | 200 |
| `/admin/api/nmea_buffer` | 404 | 200 |
| `/admin/api/nmea_stream` | 404 | 200, text/event-stream |
| Config request with remote Host/forwarded headers, no auth | 401 | 401 |
| LAN admin config without credentials | Not checked | 401 |
| Native API over USB loopback | Not checked | 200 |
| Native API over LAN | Not checked | Connection refused; LAN admin on the same phone remained reachable |
| Background service / return to app | Not checked | Health 200 while backgrounded; UI rendered after return |
| Fatal Java/native crash or ANR during smoke test | None observed | None observed |

Log noise: GPU shader compilation warnings on baseline; a NanoHTTPD broken pipe after intentionally disconnecting the SSE test client. Neither was an observed app crash. Exit history records the expected package-update process termination.

Limits: no USB SDR attached, no account sign-in, MQTT disabled, remote tunnel disabled, no location permission granted. This does not validate radio reception, GPS fixes, MQTT/tunnel recovery, newer target-SDK behavior, a minified release on-device, or long-duration stability. No settings/credentials or raw device logs are committed.

A new navy/white/turquoise vessel-and-radio logo was subsequently added as the launcher artwork, including a legacy icon fallback. The previous audit document describes the state before this icon addition; its missing-legacy-icon finding is addressed by this change.

Final icon build: versionName `3.0-1014-1549` installed successfully as an in-place update; cold launch completed in 328 ms and the health endpoint reported the service running. The new logo is bundled in adaptive and legacy launcher resources. USB forwarding used for the tests is removed at completion; the app remains installed and running.
