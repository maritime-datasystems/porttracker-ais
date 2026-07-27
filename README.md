# PortTracker AIS

A minimal headless AIS receiver service for Android. This app runs as a background service, receiving AIS messages from SDR devices and forwarding them to configured outputs.

## Features

- **Headless Operation**: Runs as an Android foreground service (wake + WiFi lock, battery-exemption prompt, sticky restart)
- **USB SDR Support**: RTL-SDR, AirSpy, AirSpy HF+
- **Network Sources**: RTL-TCP, SpyServer
- **Multiple Outputs**:
  - MQTT to PortTracker/TrustedDocks (provisioned via API key, `aisc-json` or raw)
  - UDP (NMEA or JSON, up to 4 targets) and TCP listener
  - Built-in web interface with live map, NMEA stream (SSE/polling), and admin API
- **Remote Access**: FRP tunnel per station (`https://<station>.…`), Basic-auth gated
- **Vessel Cache**: persistent SQLite store of static vessel data + tiered position history
- **GPS Forwarding**: phone GPS/compass as `$GPRMC/$GPGGA/$HEHDT` over UDP
- **OTA Updates**: manifest-driven APK self-update from the admin UI
- **Auto-Start**: On boot or USB device connection

Full component/threading/API documentation: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**

## Requirements

- Android 6.0+ (API 23+)
- USB OTG support (for USB SDR devices)
- Android SDK 34, NDK 25.1.8937393, CMake 3.22.1 (managed by the Gradle build)

## Building

This project is **self-contained**: the native C++ sources (AIS-catcher,
libusb, rtl-sdr, airspy) are vendored under `app/src/main/jni/`, so no sibling
checkout is required.

```bash
# Build (native sources are already in-tree)
./gradlew assembleDebug
```

The vendored native sources originate from
[jvde-github/AIS-catcher-for-Android](https://github.com/jvde-github/AIS-catcher-for-Android)
(GPLv3 — see `app/src/main/jni/AIS-catcher/LICENSE`). To refresh them from an
upstream checkout, use `scripts/vendor-native.sh` (see the script header).

> Note: the FRP client binary (`libfrpc.so`) is not vendored — fetch it with
> `scripts/fetch-frpc.sh` if you need remote access (see below).

## Remote access (FRP) binary

Remote access tunnels the local web interface to the PortTracker FRP server
using the `frpc` client. That binary is **not committed** to the repo (~13-15 MB
per ABI) and is gitignored. Fetch it before building if you need remote access:

```bash
scripts/fetch-frpc.sh          # defaults to frp 0.61.2 (compatible with the PortTracker frps)
# or pin a version:
scripts/fetch-frpc.sh 0.61.2
```

This downloads `frpc` from the [frp releases](https://github.com/fatedier/frp/releases)
and stages it as `app/src/main/jniLibs/<abi>/libfrpc.so` for arm64-v8a,
armeabi-v7a and x86_64. Android executes it from the app's native library
directory at runtime (see `FrpTunnelManager`).

The app **builds and runs without it** — remote access just won't start
(`FrpTunnelManager` logs "Binary not found in native lib dir" and the rest of
the app is unaffected). Choose a frpc version compatible with your frps; any
frpc >= 0.52 supports the TOML config the app generates.

## Configuration

### Via Settings UI
Open the app to access the settings screen where you can configure:
- SDR device type
- UDP output (host, port, format)
- TCP listener port
- Web interface port  
- Auto-start options

### Via Web Interface
Access `http://<phone-ip>:8080` from any browser when the service is running.

## Architecture

```
┌─────────────────────────────────────────────┐
│              AIS PortTracker                │
├─────────────────────────────────────────────┤
│  SettingsActivity     │  UsbDeviceReceiver  │
│  (Minimal Config UI)  │  (Auto-detect SDR)  │
├───────────────────────┴─────────────────────┤
│           AisReceiverService                │
│     (Foreground Service + Wake Lock)        │
├─────────────────────────────────────────────┤
│            AIScatcherNDK (JNI)              │
│  ┌─────────┐ ┌─────────┐ ┌─────────────┐   │
│  │ RTL-SDR │ │ AirSpy  │ │ AIS Decoder │   │
│  └─────────┘ └─────────┘ └─────────────┘   │
├─────────────────────────────────────────────┤
│               Data Outputs                  │
│  ┌─────┐  ┌──────────┐  ┌───────────────┐  │
│  │ UDP │  │ TCP Port │  │ Web Interface │  │
│  └─────┘  └──────────┘  └───────────────┘  │
└─────────────────────────────────────────────┘
```

## License

Uses native code from AIS-catcher (Copyright 2021-2024 jvde.github@gmail.com). 
See the original project for license details.
