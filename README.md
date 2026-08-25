# CatSmoker v1.8.2 – Ultimate FPS Booster & Gaming Performance Hub

**CatSmoker** is a comprehensive Android optimization utility designed to unlock the full potential of your device for gaming. By leveraging advanced system-level hooks and file manipulation techniques, CatSmoker enables higher frame rates (FPS), custom resolutions, and enhanced gameplay precision.

---

## 🚀 Key Features

### 🛠️ Core Optimization
- **Device Spoofing**: Unlock higher graphics settings and FPS by mimicking premium device models (Supports LSPosed & Shizuku).
- **Resolution Changer**: Customize your display resolution and density (DPI) to balance performance and visual clarity.
- **File Engineering**: Directly modify game configuration files to tweak hidden settings (SAF, Shizuku, or manual export).

### 🎮 Gaming Tools
- **Real-Time Telemetry**: Monitor FPS, CPU/RAM usage, power consumption, and device temperature via a non-intrusive overlay.
- **Crosshair Overlay**: Add a customizable crosshair for improved aiming in FPS and battle royale games.
- **DNS Optimizer**: Reduce latency and improve connection stability with custom gaming DNS profiles.
- **Game Library**: A centralized hub to launch and manage your games with per-app optimization.

### ⚙️ System & Advanced
- **Engineering Console**: Detailed system logs and diagnostics for troubleshooting.
- **Root & Non-Root Support**: Optimized workflows for both rooted (LSPosed) and non-rooted (Shizuku) devices.
- **Privacy First**: No unnecessary data collection; all modifications are performed locally.

---

## 📋 Table of Contents

1. [How It Works](#how-it-works)
2. [Installation Guide](#installation-guide)
3. [Usage](#usage)
4. [Supported Games](#supported-games)
5. [Device Compatibility](#device-compatibility)
6. [License](#license)
7. [Contributing](#contributing)

---

## 🔍 How It Works

CatSmoker operates by bridging the gap between hardware limitations and software potential.

### Root Method (LSPosed/Xposed)
Utilizes the LSPosed framework to hook into game processes at runtime. This allows for seamless device property spoofing without modifying any game files, making it the most robust and "undetectable" method.

### Non-Root Method (Shizuku / SAF)
- **Shizuku**: Uses the Shizuku API to gain elevated permissions on Android 11+, allowing direct modification of game data folders without root.
- **SAF (Storage Access Framework)**: Provides a way for users to manually grant access to game directories for file-based optimizations.
- **Export Mode**: Prepares optimized files that users can manually move using tools like ZArchiver.

---

## 📥 Installation Guide

### For Rooted Devices (Recommended)
1. **Prerequisites**: Magisk (v24+) or KernelSU installed.
2. **Setup Zygisk**: Enable Zygisk in your root manager settings.
3. **Install LSPosed**: Flash the latest [LSPosed (Zygisk)](https://github.com/LSPosed/LSPosed/releases) module and reboot.
4. **Enable CatSmoker**:
   - Open the **LSPosed Manager**.
   - Navigate to **Modules** and enable **CatSmoker**.
   - Select the games you want to optimize in the module's scope.
5. **Apply**: Force stop the selected games to let the hooks take effect.

### For Non-Rooted Devices (Shizuku)
1. **Install Shizuku**: Download from the [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).
2. **Activate Shizuku**: Follow the in-app instructions (Wireless Debugging or ADB via PC).
3. **Authorize**: Open CatSmoker and grant Shizuku permission when prompted.
4. **Select Game**: Choose your game and use the "Apply with Shizuku" method.

---

## 🎮 Supported Games

CatSmoker supports over 50+ popular titles, including:
- **Call of Duty: Mobile / Warzone**
- **PUBG Mobile / BGMI / New State**
- **Free Fire / Free Fire MAX**
- **Genshin Impact / Honkai: Star Rail**
- **League of Legends: Wild Rift**
- **Farlight 84 / Apex Legends (Mobile)**
- **Minecraft**
- *And many more...*

> [!TIP]
> To request support for a new game, please provide the full package name in our [Issue Tracker](https://github.com/catsmoker/com.catsmoker.app/issues).

---

## 📱 Device Compatibility

- **Android Version**: 8.1 (API 27) up to Android 15 (API 35+).
- **Tested Devices**: 
  - Lenovo Legion Tab (TB-8505X)
  - Nothing Phone (2a)
  - Xiaomi Redmi Note 13 Series
  - Pixel 8/9 Series (Android 14/15)

---

## 🛡️ Disclaimer
**Warning**: Modifying game files or spoofing device identity may violate some games' Terms of Service. Use CatSmoker responsibly. The developers are not responsible for any account bans or hardware issues.

---

## 📄 License
Licensed under the **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License**. See [LICENSE](LICENSE) for more details.

---

## 🤝 Contributing & Support
- **Report Bugs**: [GitHub Issues](https://github.com/catsmoker/com.catsmoker.app/issues)
- **Watch Tutorials**: [YouTube Guide](https://youtu.be/Ie0vEiQaQek)
- **Donate**: Support the project via [PayPal](https://www.paypal.me/catsmoker)



