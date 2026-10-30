# 📺 TvAfrTools

[![Latest Release](https://img.shields.io/github/v/release/TSCNEO/TvAfrTools?color=38BDF8&label=Release&display_name=tag&logo=github)](https://github.com/TSCNEO/TvAfrTools/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-22C55E?logo=android)](https://github.com/TSCNEO/TvAfrTools)
[![Android Version](https://img.shields.io/badge/Android%20TV-9.0%20to%2014-F59E0B)](https://github.com/TSCNEO/TvAfrTools)
[![License](https://img.shields.io/badge/License-GPLv3%20%2F%20Open%20Source-blue)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/TSCNEO/TvAfrTools)](https://github.com/TSCNEO/TvAfrTools/issues)

**TvAfrTools** is a lightweight, open-source native utility suite for **Android TV and Google TV** (Android 9.0 to Android 14). It unlocks essential cinema and productivity capabilities that are usually missing, broken, or locked behind paid apps on Android TV streaming boxes and smart TVs.

> 🇪🇸 **[Leer esta guía en Español](README_ES.md)**

---

<p align="center">
  <img src="app/src/main/res/drawable-xhdpi/ic_tv_banner.png" alt="TvAfrTools Banner" width="560"/>
</p>

---

## ✨ Key Features

### 🎬 1. Universal Auto Frame Rate (AFR) & Judder-Free Playback
- **True Frame Rate Matching**: Automatically detects video fps (23.976p, 24.0p, 25p, 50p, 59.94p, 60p) and matches your TV's HDMI refresh rate for perfectly smooth, judder-free panning.
- **Hardware Widevine L1 Tunnel Compatible**: Works even with hardware-protected DRM streams (Disney+, Netflix, Prime Video, DAZN, etc.) through a hybrid SurfaceFlinger & privileged daemon architecture.
- **Auto-Restore on Home**: Instantly returns to 60Hz as soon as you exit playback back to your launcher (Android TV Home, Google TV, or Projectivy Launcher).
- **Manual Quick Switcher**: Interactive on-screen D-Pad overlay to force any refresh rate or revert to 60Hz with a single click.

### ⚡ 2. Autonomous Local ADB Bootstrap (Zero PC Required)
- **1-Click Self-Authorization**: Features an embedded local ADB engine (`127.0.0.1:5555`).
- Grants elevated permissions (`WRITE_SECURE_SETTINGS`, `PACKAGE_USAGE_STATS`, etc.) directly on your TV without requiring a PC, Mac, or mobile phone.
- Android 13 & 14 **Restricted Settings** bypass included out of the box.

### 🎮 3. Remote Button Remapper with Anti-Bounce Filter
- **Custom Triggers**: Assign actions to Single Click, Double Click, and Long Press.
- **Context-Aware Triggers**: Define special actions that only trigger while watching full-screen video.
- **Hardware Debounce Engine (220ms)**: Eliminates annoying ghost double-clicks caused by Bluetooth remotes and Samsung / LG HDMI-CEC loopbacks.

### 🗂 4. Universal Multitasking & Deep RAM Cleaner
- **Replaces Missing Recents**: Brings a full Recents app switcher to devices without a native multitasking button or when using custom launchers (Projectivy Launcher, FLauncher, ATV Launcher).
- **Deep RAM Liberation**: Scans running user processes (`ps -A`) and performs dual termination (`am force-stop` + SDK level), freeing memory and displaying the exact MB reclaimed in real time.
- **Quick Switch & Force Stop**: Switch between apps with D-Pad `OK`, or long-press `OK` to force-stop a single app.

### 🌑 5. Real Screen Off (Background Audio Mode)
- Powers off the TV/display panel while background audio continues playing.
- Ideal for listening to **Spotify**, **YouTube**, podcasts, or radio on OLED / LED TVs without screen burn-in or unnecessary power consumption. Press any remote key to turn the screen back on instantly.

### 📊 6. Technical Live HUD & OSD
- Real-time floating overlay showing:
  - Exact HDMI Resolution & Hz
  - Real Video FPS & rendering source (SurfaceFlinger vs Widevine L1 hardware tunnel)
  - SoC / CPU Temperature (°C)
  - Live Download / Upload Network Speed (Kb/s, Mb/s)
  - Free & Total RAM

### 🌐 7. Mobile Web Remote
- Built-in lightweight HTTP server (port `8080`).
- Open your TV's IP address on any phone, tablet, or PC to control the TV, navigate with a virtual D-Pad, or **paste passwords and long URLs** directly with your phone's keyboard.

### 🔄 8. In-App GitHub Auto-Updater
- Checks for updates directly against the official GitHub Releases API.
- Downloads and updates the app right on your TV without needing to sideload manually ever again.

---

## 📺 Supported Devices & Requirements

- **Operating System**: Android TV / Google TV 9.0 (API 28) up to Android 14 (API 34).
- **Target Platforms**: **Certified Android TV & Google TV only (Android 9.0 to 14)**.
- **Tested & Verified Devices**:
  - Homatics Box R 4K Plus / SEI Robotics boxes (Amlogic S905X4)
  - Xiaomi Mi TV Box S (1st & 2nd Gen) / Mi TV Stick 4K
  - Chromecast with Google TV (HD & 4K)
  - NVIDIA Shield TV / Shield TV Pro
  - Nokia Streaming Box 8000 / 8010
  - Strong Leap-S1 / S3 & other standard Google TV / Android TV boxes.
- ⛔ **Amazon Fire TV / Fire OS is NOT supported**:
  - Amazon deliberately blocks local loopback ADB connections (`127.0.0.1:5555`) in Fire OS updates (CVE-2024-27350), preventing autonomous on-device self-activation.
  - Fire OS hides third-party accessibility services from the system UI.
  - Fire OS runs a proprietary MediaTek display stack that blocks background refresh rate switching (AFR) for third-party streaming apps.

---

## 🚀 Quick Start / Installation

1. **Download the APK**:
   - Download the latest [TvAfrTools.apk](https://github.com/TSCNEO/TvAfrTools/releases/latest/download/TvAfrTools.apk). This permanent link always downloads the latest stable release.
   - Alternatively, enter the short link into the **Downloader** app on your TV.
2. **Install & Grant Permissions**:
   - Open TvAfrTools on your TV.
   - When prompted, click **"⚡ Activar automáticamente en 1 clic"**.
   - If a dialog appears on screen asking *"Allow USB debugging?"*, check *"Always allow from this computer"* and press **OK**.
3. **Enjoy judder-free movies & smooth multitasking!**

---

## 🤝 Community & Reporting Issues

Found a bug or have a feature idea? We would love your feedback!
- 🐛 **Report a Bug**: [Open an issue on GitHub](https://github.com/TSCNEO/TvAfrTools/issues/new?template=bug_report.md)
- 💡 **Request a Feature**: [Suggest a feature](https://github.com/TSCNEO/TvAfrTools/issues/new?template=feature_request.md)
- ⭐ If you find TvAfrTools useful, star the repo on GitHub!

---

## 📜 License
TvAfrTools is open-source software licensed under the GPLv3 / Apache 2.0. Feel free to inspect, modify, and contribute.

## Roadmap

[Roadmap](ROADMAP.md)
