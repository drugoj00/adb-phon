[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md)

🌐 All our programs: https://drugoj00.github.io/

<img src="https://drugoj00.github.io/phone-in-window-with-sound/adb_icon.png" alt="" width="96" align="right">

# ADB-phon

Free ADB manager right on your phone: install APKs over ADB, air mouse and remote control, special access that survives a reboot. No computer, no root.

ADB-phon connects to the ADB of the same phone (or of another device in your network) and runs commands with shell rights, as if the phone were plugged into a computer.

⭐ If the app is useful, please star this repository: it costs nothing and helps others find it.

## Download

**Version 1.0.** [ADB-phon-1.0.apk](https://github.com/drugoj00/adb-phon/releases/latest) (Assets) · [Releases](https://github.com/drugoj00/adb-phon/releases)

SHA-256: `deb7157008a141eb00553e7d403a02139db37c89be24d47009f8056c43724de8`

## Features

- **Install APK over ADB.** Pick a file and the app is installed or updated through ADB, no computer needed.
- **Air mouse and mouse.** The phone’s gyroscope works as a pointer. A real cursor appears on the screen of the controlled device (a helper creates a virtual mouse). Click, scroll, OK, Back, Home, Menu, arrows, volume, sound and microphone on/off.
- **Special access, survives reboot.** Turn on an accessibility service, keep it enabled after a reboot (Android 13–14 often switch it off), remove “restricted settings”, allow writing secure settings, draw over other apps, notifications, usage stats, all files, install unknown apps, no battery limits.
- **Remote control.** Four switchable modes: Keys, Touch, Mouse, Air. Home, Back, Menu, D-pad and OK, volume, media keys, power, recents, text input, touch pad. For phones, TVs and set-top boxes.
- **Easy connection.** Finds devices in the network and shows their names, favourites with your own names, manual IP. Android 11+: pairing by code (wireless debugging). Android 8–10: one command from a computer once. Control another device by IP; press and hold a device to disconnect.
- **Apps.** Icons, search, filters (user, system, disabled, removed), disable and enable, uninstall and restore, stop, clear data, save APK, permission switches, batch actions, protection of critical packages.
- **Files.** Browse folders including Android/data, create, rename, copy, move, delete.
- **Phone.** Device info, monitor, screenshot, screen recording, clear all caches, quick settings (dark mode, Wi-Fi, data, Bluetooth, rotation, font, screen timeout, show touches, animations, DPI), reboot (normal, recovery, bootloader).
- **Console.** Any shell command.
- **Settings.** Theme (auto, light, dark), language (English, Ukrainian, Russian), keep screen on, bug log, backup and restore of settings to a folder you choose, install any version of the program from GitHub.
- **Free.** No ads, open source.

## Screenshots

![Connection: favourites with names and devices found in the network.](screenshots/en/01-connect.png)

![Apps with icons, search, filters and “Install APK”.](screenshots/en/02-apps.png)

![Actions on an app: permissions, special access, disable, uninstall, stop, save APK.](screenshots/en/03-app-actions.png)

![Special access: accessibility service that stays on after a reboot, restricted settings, secure settings.](screenshots/en/04-special-access.png)

![Remote control: keys, D-pad, volume, media, text input.](screenshots/en/05-remote.png)

![Air mouse: gyroscope pointer, click, arrows, Back, Home, Menu, sound and microphone on/off.](screenshots/en/06-air-mouse.png)

![Phone: device info, monitor, screenshot, recording, quick settings.](screenshots/en/07-phone.png)

![Settings: theme, language, backup of settings, program versions.](screenshots/en/08-settings.png)

![Console: any shell command.](screenshots/en/09-console.png)

## Quick start

1. Download ADB-phon-1.0.apk and install it (allow installing from this source if Android asks).
2. Android 11 and newer: Settings, Developer options, turn on “Wireless debugging”, tap “Pair device with pairing code”. Open ADB-phon in split screen or a floating window, enter the port and the code, tap “Pair”, then “Connect”.
3. Android 8–10: once, from a computer with USB debugging, run adb tcpip 5555. In ADB-phon tap “Connect” and allow debugging on the phone.
4. For another device (TV, second phone) tap it in the “Devices in the network” list or type its IP.

## Requirements

Android 8.0 or newer (ARMv7, ARM64, x86, x86_64). Wi-Fi for wireless debugging (Android 11+). Android 8–10: one command from a computer once (adb tcpip 5555), repeated after a reboot. Tested on Android 10 (Samsung A7) and Android 15 (Samsung A14).

## Limitations and warnings

- Actions on system apps can break the phone: be careful, the app asks for confirmation and protects critical packages.
- ADB-phon works locally and sends nothing anywhere. The cursor helper and the remote control manage a whole device: connect only to your own devices.
- The app is not on Google Play (it would not be allowed): download the APK here. Android may warn about installing from an unknown source.
- Tested on two phones (Android 10 and 15). Not yet tested on TVs and set-top boxes, nor on Android 8–9 and 11–14 devices of other makers; reports are welcome.
- Special access settings can differ on Xiaomi, Huawei and other firmwares; on Xiaomi also turn on “USB debugging (security settings)”.

## Built with

Kotlin and Jetpack Compose; ADB client libadb-android (MuntashirAkon, GPL-3.0 or Apache-2.0) and Conscrypt (Apache-2.0). The app is independent and not related to Google. Licensed under GPL-3.0.

## News and help

- Telegram: [t.me/PhoneInWindowSound](https://t.me/PhoneInWindowSound)

## Support the project

The app is free. If it is useful you can support its development by card, Google Pay or USDT. For USDT use only the TRON network (TRC20): coins sent over another network are lost.

- By card or Google Pay (from 50 UAH): https://secure.wayforpay.com/button/bba92579a4647
- USDT (TRON TRC20): `TXk32CLkwJryTqhmkw6urcM5CacJgPsSpJ`
