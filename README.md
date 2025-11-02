# Catsmoker App – Boost FPS & Enhance Gaming Performance

**Catsmoker** is a powerful Android app designed to unlock higher FPS in games by spoofing your device model. It offers two methods for achieving this: a root method using LSPosed and non-root methods using Shizuku or SAF (Storage Access Framework).

---

## Key Features

- **Device Spoofing**: Improve gaming performance by mimicking a different device model.
- **DNS Changer**: Optimize your network connection for a better gaming experience.
- **Wide Game Compatibility**: Works with numerous popular mobile games.
- **Easy Setup**: Minimal configuration required for quick activation.
- **Crosshair Overlay**: Enhances aiming precision in FPS games.
- and more...

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [Getting Started](#getting-started)
3. [Installation Guide](#installation-guide)
4. [Usage](#usage)
5. [Supported Games](#supported-games)
6. [License](#license)
7. [Contributing](#contributing)
8. [Donation](#donation)

---

## How It Works

Catsmoker improves gaming performance by tricking games into thinking your device is a different, more powerful model. This can unlock higher graphics settings and frame rates that would otherwise be unavailable.

There are two main ways to use Catsmoker:

### Root Method (LSPosed)

This method uses the LSPosed framework to directly hook into the game's process and modify the device properties it sees. This is the most powerful and reliable method, as it allows for deep system-level modifications.

### Non-Root Methods

For non-rooted devices, Catsmoker provides options for modifying game files:

- **Shizuku**: This method uses the Shizuku service to gain the necessary permissions to modify device properties. **Note:** The current implementation of the Shizuku method in Catsmoker is outdated and only supports **Android 11 and below**. It uses a version of Shizuku from 2021 (v12.1.0) that does not support Android 12 and above.

- **SAF (Storage Access Framework)**: For devices running **Android 12 and above**, you can use the Storage Access Framework to manually select and modify game files. This method is currently limited to games where file manipulation is straightforward, such as PUBG Mobile.

- **ZArchiver**: For manual file replacement, you can use ZArchiver to move files after they are prepared by Catsmoker.

**We are actively working on updating the Shizuku method to support modern Android versions. In the meantime, the root method is recommended for the best experience.**

---

## Getting Started

- **Watch the Tutorial**: [YouTube Guide](https://youtu.be/Ie0vEiQaQek)

---

## Installation Guide

### For Rooted Devices

1.  **Check Root Access**
    - Verify root using [Root Checker](https://play.google.com/store/apps/details?id=com.joeykrim.rootcheck&hl=en) or by checking the in-app status.

2.  **Install Magisk (v29.0 and up Recommended)**
    - Download [Magisk](https://github.com/topjohnwu/Magisk/releases).

3.  **Install Zygisk Next**
    - Install the [Zygisk Module](https://github.com/Dr-TSNG/ZygiskNext) via Magisk.

4.  **Install LSPosed (YALo)**
    - Get the latest [LSPosed (YALo)](https://github.com/LSPosed/LSPosed/releases) via Magisk.

5.  **Reboot**

6.  **Open LSPosed Manager from notification**

7.  **Enable Catsmoker in LSPosed**
    - Open **LSPosed Manager** → **Modules** → Enable **Catsmoker**.
    - Select the games you want to apply spoofing to within LSPosed's scope settings.

8.  **Force Stop Games**
    - Manually **force stop** the game to apply changes.

### For Non-Rooted Devices (Shizuku)

1.  **Install Shizuku**: Download and install the [Shizuku app](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) from the Play Store.
2.  **Start Shizuku Service**: Follow the instructions within the Shizuku app to start its service. This usually involves connecting to a PC for ADB activation or using a wireless ADB method.
3.  **Grant Permissions**: Open Catsmoker and grant it the necessary permissions when prompted by Shizuku.
4.  **Select Game**: In Catsmoker, select the game you wish to modify.
5.  **Apply with Shizuku**: Choose the "Apply with Shizuku" option. This will attempt to modify game files using Shizuku's elevated privileges.

---

## Usage

### Rooted Devices

Once installed and enabled in LSPosed, Catsmoker automatically spoofs device info for supported games. No extra setup is needed!

-   **Select Games**: Choose the desired apps within **LSPosed Manager** to include in Catsmoker's scope.
-   **Reboot Required**: Some changes may require a device restart to take effect.

### Non-Rooted Devices

1.  Select a game from the list within Catsmoker.
2.  If you are on **Android 11 or below**, you can use the **Shizuku** method.
3.  If you are on **Android 12 or above**, you must use the **SAF** method.
4.  For manual file manipulation, you can use the **ZArchiver** option after Catsmoker prepares the files.

---

**Warning**: Device spoofing and file manipulation may violate some games' Terms of Service. Use at your own risk.

---

### Request Game Support

Want a new game added? Submit an issue with the **full APK package name** (e.g., `com.activision.callofduty.warzone`) here:
- **GitHub Issues**: [https://github.com/catsmoker/com.app.catsmoker/issues](https://github.com/catsmoker/com.app.catsmoker/issues)

---

## License

- **Apache License** – See [LICENSE](LICENSE) for details.

---

## Contributing

We welcome contributions! Report bugs, suggest improvements, or submit pull requests.

- **Open an Issue**
- **Fork & Submit PR**

---

## To-Do List

- [ ] Update Shizuku implementation to support Android 12+
- [ ] Add a cleaning feature
- [ ] Add a resolution scaling feature to improve FPS
- [ ] Improve the overall UI/UX of the app

---

## Donation

If you find Catsmoker useful, consider supporting its development:
- [PayPal](https://www.paypal.me/catsmoker)
