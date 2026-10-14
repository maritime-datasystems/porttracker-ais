cmake_minimum_required(VERSION 3.22.1)
project("AIScatcherNDK")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math")

# Native AIS-catcher sources are vendored into this directory (self-contained build).
# Originally from https://github.com/jvde-github/AIS-catcher-for-Android (GPLv3) —
# see AIS-catcher/LICENSE. Re-sync with scripts/vendor-native.sh.
set(AIS_CATCHER_SRC "${CMAKE_CURRENT_SOURCE_DIR}")

# Include directories
include_directories(
    ${AIS_CATCHER_SRC}/libusb/android
    ${AIS_CATCHER_SRC}/libusb/libusb
    ${AIS_CATCHER_SRC}/libusb/libusb/os
    ${AIS_CATCHER_SRC}/rtl-sdr/include
    ${AIS_CATCHER_SRC}/AIS-catcher/Source
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Application
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/IO
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Library
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Tracking
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/JSON
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/DBMS
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/DSP
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Utilities
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Aviation
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Marine
)

add_definitions(-DHASRTLSDR -DHASRTLSDR_BIASTEE -DHASRTL_ANDROID -DHASRTLSDR_TUNERBW)

# Define the native library
add_library(
    AIScatcherNDK
    SHARED
    
    # libusb
    ${AIS_CATCHER_SRC}/libusb/libusb/core.c
    ${AIS_CATCHER_SRC}/libusb/libusb/descriptor.c
    ${AIS_CATCHER_SRC}/libusb/libusb/hotplug.c
    ${AIS_CATCHER_SRC}/libusb/libusb/io.c
    ${AIS_CATCHER_SRC}/libusb/libusb/sync.c
    ${AIS_CATCHER_SRC}/libusb/libusb/strerror.c
    ${AIS_CATCHER_SRC}/libusb/libusb/os/linux_usbfs.c
    ${AIS_CATCHER_SRC}/libusb/libusb/os/events_posix.c
    ${AIS_CATCHER_SRC}/libusb/libusb/os/threads_posix.c
    ${AIS_CATCHER_SRC}/libusb/libusb/os/linux_netlink.c
    
    # RTL-SDR
    ${AIS_CATCHER_SRC}/rtl-sdr/src/librtlsdr.c
    ${AIS_CATCHER_SRC}/rtl-sdr/src/tuner_e4k.c
    ${AIS_CATCHER_SRC}/rtl-sdr/src/tuner_fc0012.c
    ${AIS_CATCHER_SRC}/rtl-sdr/src/tuner_fc0013.c
    ${AIS_CATCHER_SRC}/rtl-sdr/src/tuner_fc2580.c
    ${AIS_CATCHER_SRC}/rtl-sdr/src/tuner_r82xx.c
    
    
    
    # AIS-catcher core (matching original CMakeLists.txt)
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Application/Receiver.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Application/WebDB.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Application/Config.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Tracking/DB.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Tracking/Ships.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/DBMS/PostgreSQL.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/FileWAV.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/RTLSDR.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/SDRPLAY.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/DSP/Demod.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/DSP/Model.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Marine/AIS.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/JSON/JSONAIS.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/JSON/Keys.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/IO/HTTPClient.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/FileRAW.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/HACKRF.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/UDP.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/RTLTCP.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/ZMQ.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/SoapySDR.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/SpyServer.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Marine/Message.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Marine/NMEA.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Utilities/Convert.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Utilities/Serialize.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Utilities/Parse.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Utilities/Helper.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Library/TCP.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/JSON/JSON.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/IO/Network.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/IO/HTTPServer.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/JSON/StringBuilder.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/JSON/Parser.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Device/Serial.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Aviation/ADSB.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/DSP/DSP.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Application/WebViewer.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Application/Prometheus.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/IO/Protocol.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Library/Logger.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Application/DeviceManager.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Utilities/StreamHelpers.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Aviation/Basestation.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Aviation/Beast.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Utilities/TemplateString.cpp
    ${AIS_CATCHER_SRC}/AIS-catcher/Source/Application/MapTiles.cpp
    
    # JNI bridge
    ${AIS_CATCHER_SRC}/JNI/AIScatcherNDK.cpp
)

find_library(log-lib log)

# Link libraries
target_link_libraries(
    AIScatcherNDK
    android
    ${log-lib}
)

# Android 15+ requires LOAD segments aligned to 16 KB on devices with 16 KB page size.
# Without this flag the OS shows "ELF alignment check failed" at every app launch.
target_link_options(AIScatcherNDK PRIVATE
    "-Wl,-z,max-page-size=16384"
)
