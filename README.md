<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=0:050505,50:ef233c,100:ff6b35&height=260&section=header&text=CATSMOKER&fontSize=64&fontColor=ffffff&fontAlignY=35&desc=Android%20Gaming%20Toolkit&descAlignY=58&animation=twinkling&stroke=ff6b35&strokeWidth=2" alt="CatSmoker banner" width="100%" />
</p>

<div align="center">

**Android gaming tools for performance tuning, telemetry, and precision.**

[![GitHub stars](https://img.shields.io/github/stars/catsmoker/com.catsmoker.app?style=flat-square&logo=github)](https://github.com/catsmoker/com.catsmoker.app/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/catsmoker/com.catsmoker.app?style=flat-square&logo=github)](https://github.com/catsmoker/com.catsmoker.app/network/members)
[![Open issues](https://img.shields.io/github/issues/catsmoker/com.catsmoker.app?style=flat-square&logo=github)](https://github.com/catsmoker/com.catsmoker.app/issues)
[![Last commit](https://img.shields.io/github/last-commit/catsmoker/com.catsmoker.app?style=flat-square&logo=git)](https://github.com/catsmoker/com.catsmoker.app/commits/main)
[![Repository size](https://img.shields.io/github/repo-size/catsmoker/com.catsmoker.app?style=flat-square)](https://github.com/catsmoker/com.catsmoker.app)
[![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://www.android.com/)
[![Min SDK](https://img.shields.io/badge/min%20SDK-27-546E7A?style=flat-square)](https://developer.android.com/studio/releases/platforms)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Latest release](https://img.shields.io/github/v/release/catsmoker/com.catsmoker.app?style=flat-square&label=release)](https://github.com/catsmoker/com.catsmoker.app/releases)
[![License](https://img.shields.io/badge/license-CC%20BY--NC--SA%204.0-0ea5e9?style=flat-square&logo=creativecommons&logoColor=white)](LICENSE)

[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Gradle](https://img.shields.io/badge/build-Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)](https://gradle.org/)
[![Shizuku](https://img.shields.io/badge/access-Shizuku-2E7D32?style=flat-square)](https://shizuku.rikka.app/)
[![LSPosed](https://img.shields.io/badge/module-LSPosed-8E44AD?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![Discord](https://img.shields.io/badge/community-Discord-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.com/invite/HQC5BwcXtS)
[![Telegram](https://img.shields.io/badge/chat-Telegram-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/CATSM0KER)
[![PayPal](https://img.shields.io/badge/support-PayPal-00457C?style=flat-square&logo=paypal&logoColor=white)](https://www.paypal.me/catsmoker)

[![Typing effect](https://readme-typing-svg.demolab.com?font=Fira+Code&size=20&duration=2800&pause=900&color=0EA5E9&center=true&vCenter=true&width=620&lines=Tune+your+game.+Measure+your+device.;Android+gaming+tools+for+serious+sessions.)](https://github.com/catsmoker/com.catsmoker.app)

[Download](https://github.com/catsmoker/com.catsmoker.app/releases) · [Report an issue](https://github.com/catsmoker/com.catsmoker.app/issues) · [简体中文](README.zh-CN.md)
</div>

**CatSmoker** is an Android optimization utility for supported games. It combines system-level integrations, game configuration tools, performance overlays, and gaming-focused controls in one app.

---

## 🚀 Key Features

### 🛠️ Core Optimization
- **Device Spoofing**: Unlock higher graphics settings and FPS by mimicking premium device models (Supports LSPosed & Shizuku).
- **Resolution Changer**: Customize your display resolution and density (DPI) to balance performance and visual clarity.
- **File Engineering**: Directly modify game configuration files to tweak hidden settings (SAF, Shizuku, or manual export).
- and more...

### 🎮 Gaming Tools
- **Real-Time Telemetry**: Monitor FPS, CPU/RAM usage, power consumption, and device temperature via a non-intrusive overlay.
- **Crosshair Overlay**: Add a customizable crosshair for improved aiming in FPS and battle royale games.
- **DNS Optimizer**: Reduce latency and improve connection stability with custom gaming DNS profiles.
- **Game Library**: A centralized hub to launch and manage your games.
- and more...

### ⚙️ System & Advanced
- **Engineering Console**: Detailed system logs and diagnostics for troubleshooting.
- **Root & Non-Root Support**: Optimized workflows for both rooted (LSPosed) and non-rooted (Shizuku) devices.
- **Privacy First**: No unnecessary data collection; all modifications are performed locally.
- and more...

## 📋 Table of Contents

- [Features](#-key-features)
- [How It Works](#-how-it-works)
- [Installation Guide](#-installation-guide)
- [Google Play Version](#google-play-version)
- [Supported Games](#-supported-games)
- [Device Compatibility](#-device-compatibility)
- [Build From Source](#build-from-source)
- [Star History](#-star-history)
- [License](#-license)
- [Contributing & Support](#-contributing--support)

---

## 🔍 How It Works

CatSmoker operates by bridging the gap between hardware limitations and software potential.

### Root Method (Xposed)
Utilizes the LSPosed framework to hook into game processes at runtime. This allows for seamless device property spoofing without modifying game files.

### Non-Root Method (Shizuku)
- **Shizuku**: Uses the Shizuku API to gain elevated permissions on Android 11+, allowing direct modification of game data folders without root.
- **SAF (Storage Access Framework)**: Provides a way for users to manually grant access to game directories for file-based optimizations.
- **Export Mode**: Prepares optimized files that users can manually move using tools like ZArchiver.

---

## 📥 Installation Guide

- **Watch Tutorial** (old but some apply): [YouTube Guide](https://youtu.be/Ie0vEiQaQek)

### Download CatSmoker: [Releases](https://github.com/catsmoker/com.catsmoker.app/releases)

### For Rooted Devices (Recommended)
## Installation

1. **Prerequisites:** Install a supported root solution: [Magisk](https://github.com/topjohnwu/Magisk/releases) (v24 or later, recommended), APatch, or KernelSU.

2. **Set Up Zygisk:** Install and enable **one** of the following Zygisk implementations in your root manager:

   * [ZygiskNext](https://github.com/LSPosed/ZygiskNext/releases)
   * [NeoZygisk](https://github.com/JingMatrix/NeoZygisk/releases)
   * [ReZygisk](https://github.com/PerformanC/ReZygisk)

3. **Choose a Framework Module:** Install **only one** of the following options. Do not install both at the same time.

   * **Recommended: [Vector](https://github.com/JingMatrix/Vector/releases)** — Flash the latest compatible release and reboot.
   * **Alternative: [LSPosed](https://github.com/LSPosed/.github/blob/master/profile/README.md)** — Flash the public release ZIP and reboot.

4. **Verify Installation:** Open LSPosed Manager, if applicable, and confirm that the framework is active.

6. *optional*: **Hide your root** by enabling **only one** of the following modules in your root manager:

   * [Shamiko](https://github.com/LSPosed/LSPosed.github.io/releases)
   * [Zygisk-Assistant](https://github.com/snake-4/Zygisk-Assistant0)
  
7.  *optional*: **Hide  USB debugging / Developer options** install [DuckUSB](https://github.com/Bouteillepleine/DuckUSB) and enable it in vector/lsposed.

8. **Enable CatSmoker**:
  - Grant it root access in your root manager.
   - Open the **LSPosed/vector Manager**.
   - Navigate to **Modules** and enable **CatSmoker**.
   - Select the games you want to optimize in the module's scope.

9. **Apply**: Force stop the selected games to let the hooks take effect, or reboot.

### For Non-Rooted Devices (Shizuku)
1. **Install Shizuku**: Download from the [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).
2. **Activate Shizuku**: Follow the in-app instructions (Wireless Debugging or ADB via PC).
3. **Authorize**: Open CatSmoker and grant Shizuku permission when prompted.
4. **Select Game**: Choose your game and use the "Apply with Shizuku" method.

> **File access note:** SAF may not work on Android 10 or later. ZArchiver may also require Shizuku to access `Android/data`.

---

## Google Play Version

Catsmoker is one project with two Gradle flavors built from `main` (see `docs/FLAVOR_WORKFLOW.md`): `full` (the complete GitHub app) and `playstore` (the Google Play distribution, built from the `playstore` flavor/source set — there is no separate Git branch).

The Google Play version is modified for **non-root users** and is designed to work without requiring root access. It excludes the Full-only capabilities (device spoofing/LSPosed, Magisk module generation, GitHub self-updater) and serves AdMob ads instead of Start.io. Some advanced features may still have requirements or limitations compared to the full version.

### Closed Testing

The Google Play version is currently available through **Closed Testing**, so it will not appear on Google Play for users who have not joined the testing group.

To access the app:

1. Join the Catsmoker Google Group:
   **catsmoker-apps@googlegroups.com**

2. After joining the group, wait **up to 5 minutes** for Google to update your testing access.

3. Then open the Catsmoker Google Play testing page:
   https://play.google.com/apps/testing/com.catsmoker.app

4. Enrol in the closed test and download Catsmoker from Google Play.

If the testing page or app does not appear immediately after joining the group, wait a few minutes and try again.

The `main` branch is the single canonical development branch: shared features are implemented once in `src/main` and ship in both flavors automatically. Developers build the Play version with `:app:assemblePlaystoreRelease`, never by switching branches.

---

## 🎮 Supported Games

CatSmoker supports over 50+ popular titles, including:
- **PUBG Mobile all variants**
- **Genshin Impact**
- **Honkai: Star Rail**
- **wuthering waves**
- **Grid Autosport**
- *And many more...*

> [!TIP]
> To request support for a new game, please provide its full package name in the [Issue Tracker](https://github.com/catsmoker/com.catsmoker.app/issues), [Discord](https://discord.com/invite/HQC5BwcXtS), or [Telegram](https://t.me/CATSM0KER).

---

## 📱 Device Compatibility

- **Android Version**: 8.1 (API 27) and newer.
- **Tested Devices**: 
  - Lenovo Legion Tab (TB-8505X)
  - Nothing Phone (2a)
  - Xiaomi Redmi Note 14
  - Poco X3 NFC
  - Realme 9 Pro Plus

---

## Build From Source

Requirements: Android Studio, Java 17, Android SDK, and the Android NDK configured by the project.

```bash
bash ./gradlew :app:assembleFullDebug :app:assemblePlaystoreDebug
```

Two distribution variants build from this branch (`docs/FLAVOR_WORKFLOW.md`):
`app/build/outputs/apk/full/debug/app-full-debug.apk` (complete GitHub app)
and `app/build/outputs/apk/playstore/debug/app-playstore-debug.apk`
(Play-safe build). Release equivalents: `:app:assembleFullRelease`
and `:app:assemblePlaystoreRelease`.


---

## 🛡️ Disclaimer
**Warning**: Modifying game files or spoofing device identity may violate some games' Terms of Service. Use CatSmoker responsibly. The developers are not responsible for account bans, hardware issues, or boot-loops.

---

## 🔒 Privacy & Terms

- **Local-first**: settings, profiles, backups and logs stay on your device. There is no account system and no project-operated server.
- **Ads**: the full build shows Start.io banner ads (disable them in Settings). The Play Store build uses AdMob instead, removable only via the one-time Google Play `remove_ads` purchase (Settings → Remove Ads) — there is no free disable switch on that variant.
- **Update checks** query the public GitHub releases API only when you ask (automatic checks are opt-in and off by default).
- **Logs and session recordings** leave the device only when you explicitly share them.
- **Donations** (PayPal, Binance, crypto) are voluntary gifts processed entirely by those external services.
- The full **Privacy Policy** and **Terms of Service** ship inside the app (About → Legal, also linked from Settings and the first-run agreement).

---

## 📄 License
Licensed under the **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License**. See [LICENSE](LICENSE) for more details.

[![CC](https://mirrors.creativecommons.org/presskit/buttons/88x31/svg/by-nc-sa.svg)](https://creativecommons.org/licenses/by-nc-sa/4.0/)

---

## 🤝 Contributing & Support

- **Donate**: Support the project via [PayPal](https://www.paypal.me/catsmoker)
- **Report Bugs**: [GitHub Issues](https://github.com/catsmoker/com.catsmoker.app/issues)
- Contributions are welcome. Please open an issue before starting a large change.

---

## 📊 Star History

Track the growth and community adoption of CatSmoker over time. Click the chart to explore detailed analytics on GitHub Star History.

<a href="https://www.star-history.com/?repos=catsmoker%2Fcom.catsmoker.app&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=catsmoker/com.catsmoker.app&type=date&theme=dark&legend=top-left&sealed_token=qwLfL4GKiYqU8CbwLbXje7-e3LbgxDUfq056kDKBui7LfYp0KAQzJqPDXlrzscvIioa0HaURCCis-jW9u3W6wKjQT7TKN4iCkSLHNwpFtelcQnvx_4TUpSlCbAq7YGkPEJOJplMjIy7I9fn7VXNFDcQMhqr3O8RAKoJ8hG5coMwtJzWRd_iKgAlaHSRo" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=catsmoker/com.catsmoker.app&type=date&legend=top-left&sealed_token=qwLfL4GKiYqU8CbwLbXje7-e3LbgxDUfq056kDKBui7LfYp0KAQzJqPDXlrzscvIioa0HaURCCis-jW9u3W6wKjQT7TKN4iCkSLHNwpFtelcQnvx_4TUpSlCbAq7YGkPEJOJplMjIy7I9fn7VXNFDcQMhqr3O8RAKoJ8hG5coMwtJzWRd_iKgAlaHSRo" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=catsmoker/com.catsmoker.app&type=date&legend=top-left&sealed_token=qwLfL4GKiYqU8CbwLbXje7-e3LbgxDUfq056kDKBui7LfYp0KAQzJqPDXlrzscvIioa0HaURCCis-jW9u3W6wKjQT7TKN4iCkSLHNwpFtelcQnvx_4TUpSlCbAq7YGkPEJOJplMjIy7I9fn7VXNFDcQMhqr3O8RAKoJ8hG5coMwtJzWRd_iKgAlaHSRo" />
 </picture>
</a>
