[English](INSTRUCTION.md) · [Русский](INSTRUCTION.ru.md) · [Українська](INSTRUCTION.uk.md) · [README](README.md)

# ADB-phon 9.0: user guide

A guide to everything in the program. Button and section names are taken from the program itself. If a step does not work, see “Limits” at the end of the section.

## Contents

- [Tabs](#tabs)
- [First run and permissions](#first-run-and-permissions)
- [Connecting over ADB](#connecting-over-adb)
- [Installing apps](#installing-apps)
- [The “Install” window and the install progress bar](#the-install-window-and-the-install-progress-bar)
- [Apps](#apps)
- [Junk cleaner](#junk-cleaner)
- [Copies and versions](#copies-and-versions)
- [Backup with data](#backup-with-data)
- [Home screen protection](#home-screen-protection)
- [Special access for an app](#special-access-for-an-app)
- [Remote](#remote)
- [Air mouse](#air-mouse)
- [PC mouse](#pc-mouse)
- [Gamepad](#gamepad)
- [SSH](#ssh)
- [Files](#files)
- [Network folders](#network-folders)
- [Cloud drives](#cloud-drives)
- [Wi-Fi server and Wi-Fi explorer](#wi-fi-server-and-wi-fi-explorer)
- [Phone](#phone)
- [Console](#console)
- [Connection over the internet and Tailscale](#connection-over-the-internet-and-tailscale)
- [Devices and Video window](#devices-and-video-window)
- [PC screen](#pc-screen)
- [Video call](#video-call)
- [Shizuku](#shizuku)
- [Settings](#settings)
- [Updates](#updates)

## Tabs

Six tabs at the bottom: Link, Apps, Files, Remote, More, Options.

- Link: ADB connection, devices on the network, “Connection over the internet”, video calls.
- Apps: app list, installation, copies.
- Files: two-pane file manager, networks and clouds.
- Remote: remote and air mouse for the controlled device.
- More: Console, Phone, SSH, Mouse, Gamepad, PC screen.
- Options: theme, colors, settings backup, Shizuku, updates.

## First run and permissions

What it is: on first launch the program asks for permissions one by one. Some features do not work without them.

How to go through it:

1. **Step 1.** Install the APK. If Android asks, allow installing from this source. On screen: the ADB-phon icon in the app list.
2. **Step 2.** Open the program. On screen: a window “Setup 1 of N: …” with the buttons “Turn on” and “Skip”. Under the step name it says why the step is needed and what will not work without it.
3. **Step 3.** Tap “Turn on”. An Android settings page opens: turn the switch on and return to the program (Back). On screen: the window “Setup 2 of N: …”. Steps in order: access to all files, notifications (Android 13+), accessibility service (Android 11+), display over other apps, background work, installing apps, Bluetooth (Android 12+), changing system settings.
4. **Step 4.** If the switch is grey or the system refuses: open App info → ⋮ menu at the top right → “Allow restricted settings”, then tap “Turn on” again. This happens on Android 13 and newer when the program was installed from a file.
5. **Step 5.** An unneeded step can be skipped with “Skip”. When all steps are done or skipped, the wizard closes and does not open by itself again.

To run it again: Options → “Check permissions”.

Limits: many TV boxes have no such settings pages. Then the permission is granted over ADB if it is connected (message “Granted via ADB.”).

## Connecting over ADB

What it is: ADB-phon connects to the ADB of this phone or of another device on the network and runs commands with shell rights. Status at the top of the Link tab: “Connected to this phone” or “Not connected”.

### Android 11 and newer, automatically

1. **Step 1.** Turn Wi-Fi on.
2. **Step 2.** Link tab → card “Connect automatically (Android 11+)” → button “Turn on the service (once)”. Android accessibility settings open: turn the ADB-phon service on.
3. **Step 3.** Return to the program and tap “Connect automatically”. The program opens Android settings itself, turns on Wireless debugging, reads the pairing code and connects. It works for three minutes after the tap.
4. **Step 4.** If developer options are off, the window “Developer options are off” appears. Tap “Open “About phone””: Settings → About phone → Software information → tap “Build number” 7 times. After that the connection continues by itself.
5. **Step 5.** Check: the text “Connected to this phone” at the top of the tab.

### Android 11 and newer, with a code

1. **Step 1.** Turn Wi-Fi on.
2. **Step 2.** Settings → Developer options → turn on “Wireless debugging”.
3. **Step 3.** Tap “Pair device with pairing code”. On screen: a window with a 6-digit code and a port. Do not close it: open ADB-phon in split screen or a floating window.
4. **Step 4.** Link tab: enter “Pairing port” and “Pairing code”, tap “Pair”. Then tap “Connect”.
5. **Step 5.** Check: the text “Connected to this phone”.

### Android 8–10

1. **Step 1.** Once, from a computer over USB, run `adb tcpip 5555`. The “Phone in Window with Sound” program has a button for it.
2. **Step 2.** In ADB-phon on the Link tab tap the connect button (“Android 8–10: ADB over network”) and allow debugging on the phone.
3. **Step 3.** After the phone reboots, repeat the command.

### Root

If the device has root, neither ADB nor a computer is needed. Tap “Connect via root” and allow root for the app. On screen: “Connected via root”. Other devices still connect over ADB. root is requested once per session, at the first action that needs it; the program does not launch su in the background.

### Another device on the network

1. **Step 1.** On the other device (TV, box, second phone) turn on network debugging (ADB, port 5555) or “Wireless debugging”.
2. **Step 2.** In ADB-phon on the Link tab tap this device in the “Devices on the network” list or enter its IP.
3. **Step 3.** On first connection the device asks “Allow debugging?”: tick “Always”.
4. **Step 4.** A long press on a device in the list opens the menu “Connect” or “Disconnect”. Favorite devices can have their own names.

Limits: Android 8–10 needs the command from a computer after every phone reboot. Without developer options ADB over Wi-Fi is not available.

## Installing apps

What it is: silent installation and updating of apps over ADB, without a computer. ADB or root is required.

1. **Step 1.** Connect ADB (see above).
2. **Step 2.** Apps → “Install apps”. Formats: APK, APKS, APKM, XAPK, ZIP with APK and OBB, archives. The parts of a package that fit this phone are chosen automatically.
3. **Step 3.** Tap “Choose a file (APK, APKS, XAPK, archive)...” or “Choose a folder with APK files...”. For a folder the program shows “APK files found: N” and a button “Install (N)”.
4. **Step 4.** Tap “Install”. On screen: progress for each file, then “Installed: N, failed: M” with the reasons.

Other sources: the built-in file manager (including flash drives), network folders, receiving files over Wi‑Fi from a browser, internet search (F-Droid, GitHub, a link). An APK can also be opened from the Files tab: you get a choice “Install with…” (“System installer” or “ADB-phon: via ADB/root”, with “Remember my choice for this type”).

Limits: without ADB and root the installation goes through the system installer with confirmation windows.

## The “Install” window and the install progress bar

What it is: after ADB-phon is chosen as the installer, the program shows the “Install” window with version information and only then installs the app.

1. **Step 1.** In Files tap an APK file: the “Install with…” window opens with a list of installers (ADB-phon, the system installer and others). In Software → “Installing apps” the “Install” window opens right away.
2. **Step 2.** Choose ADB-phon. The “Install” window opens: icon, name, package and the kind: “New install”, “Update”, “Same version (reinstall)” or “Version downgrade”.
3. **Step 3.** If the app is already installed, a “Now” / “New” table follows: “Version”, “Code”, “Signature”, “Size”, “Min API”. Differences are highlighted. Below are the lists “New permissions” and “Removed permissions”.
4. **Step 4.** Tap “Install”. The install progress bar shows bytes (“123.6 of 300 MB”) and files (“1 of 3”), with the stages “Preparing the file…”, “Sending the file…”, “Installing…”. At the end: “Done: all packages installed” or “Not installed:” with the reason.
5. **Step 5.** The “In the background” button closes the window and the install goes on: the notification shows the progress (“Install · n of N”, MB) and the result; with several files the notification has “Stop”.

A different signature: the window warns that the version will not install over the installed one; the installed app has to be uninstalled first (its data is lost). The install is not attempted, and the result shows the reason.

Downgrade:

- Without root on Android 14 and newer a downgrade cannot keep the data. The button “Downgrade, delete data”: the program saves a copy of the current version, removes the app and installs the older one. If that fails, the current version is put back from the copy (the data is not kept).
- With root the button “Downgrade and keep data”: the program saves the app data to a copy, installs the older version and puts the data back. On failure it restores the current version and its data.
- On Android below 14 a downgrade goes the previous way and keeps the data.
- System apps cannot be downgraded.

Limits: for APKS and XAPK packages there is no rollback on a downgrade. For files from USB drives and network folders the window shows only the name and size.

## Apps

What it is: the list of installed apps with actions on them. The Apps tab.

1. **Step 1.** Connect ADB or root.
2. **Step 2.** Filters: “User”, “System”, “Disabled”, “Removed”, “All”, “Junk”. There is a search.
3. **Step 3.** Tap an app: “Open”, “Stop”, “Disable” / “Enable”, “Uninstall”, “Clear cache”, “Clear data”, “Export APK”, permissions as switches.
4. **Step 4.** For several apps, tick them: the panel “Actions for selected apps” appears (batch disable, uninstall and so on). The button “Clean RAM” frees memory.

Limits: removing a system app can break the phone. The program asks for confirmation and protects critical packages. A removed system app comes back with “Reinstall” in the “Removed” filter. “Clear data” cannot be undone.

## Junk cleaner

What it is: the “Junk” filter shows known unneeded system apps (Samsung, Google, Microsoft, Facebook services, partner apps) marked “safe” and “careful”.

1. **Step 1.** Apps → the “Junk” filter. On screen: the list and the hint “Careful! Turning off is reversible.”
2. **Step 2.** Tap “Select safe”.
3. **Step 3.** Tap “Disable” in the action panel.
4. **Step 4.** To undo: the “Disabled” filter → “Enable”.

## Copies and versions

What it is: before an app is removed the program saves its APK. Any version can be installed back, or you can roll back.

1. **Step 1.** Choose an app on the Apps tab and tap “Versions” (or “Roll back version”).
2. **Step 2.** Choose a source: “Saved copies”, “Versions from F-Droid” or “From an APK file...”.
3. **Step 3.** Tap “Install”. On screen: “Installed: version (number)”.
4. **Step 4.** All copies: Options → “Saved copies and versions...”. Past versions can be deleted (“Delete saved old versions”); the current one stays.

For versions of ADB-phon itself: Options → “Versions / roll back…” (download from GitHub or take from copies on the phone).

Limits: going to an older version needs ADB. App data is not saved in a plain copy (see “Backup with data”).

## Backup with data

What it is: a copy of an app together with its data, and restore. Folder: ADB-phon/Backups.

1. **Step 1.** Apps: tick apps and tap “Backup with data”. On screen: the steps “saving the app”, “saving external data”, “saving app data”. The program taps the system confirmation window itself.
2. **Step 2.** List of copies: “Backups with data...”. Buttons “Restore” and “Delete”.
3. **Step 3.** On restore the app’s current data is replaced with the data from the copy.

Limits: with root everything is saved. Over ADB only what the system allows: external data and, if the app permits, internal data on Android 10 and older. From Android 12 the data of such an app is not available without root.

## Home screen protection

If you disable or remove the only home screen (launcher), the program warns: “This is the only home screen”. Tap “Install App Menu”: it is downloaded from GitHub and installed. Then open “App Menu” and turn on the mode “Instead of the native app menu”. The internet is required.

## Special access for an app

What it is: rights that Android normally gives only through settings. They survive a reboot.

1. **Step 1.** Apps → choose an app → “Special access”.
2. **Step 2.** In the list: “Enable accessibility service”, “Allow writing secure settings”, “Draw over other apps”, “Usage access”, “All files access”, “Install unknown apps”, “No battery restrictions”, “Notification access”, “Modify system settings”, “Remove "restricted settings" limit”.
3. **Step 3.** For the accessibility service tap “Keep service enabled after reboot”. On screen: “Pinned: will be re-enabled on every boot”. To undo: “Stop keeping it enabled”.

Limits: ADB or root is required. On Android 13–14 the service often turns itself off, which is why pinning exists.

## Remote

What it is: the Remote tab controls the connected device (phone, TV, box).

1. **Step 1.** Connect to the device on the Link tab.
2. **Step 2.** Choose a mode: “Keys”, “Swipe”, “Mouse” or “Air”.
3. **Step 3.** “Keys”: Home, Back, Menu, Recents, D-pad and OK, volume, media keys, power, text input. “Swipe”: swipe = arrow key (suits TVs and boxes, turned on with “Arrows mode (for TVs and boxes)”), tap = OK, hold = long OK.
4. **Step 4.** “Pad on the left” / “Pad on the right” moves the panel to the needed side.

## Air mouse

What it is: the phone’s gyroscope works as a pointer. A real cursor appears on the controlled device’s screen.

1. **Step 1.** Remote → mode “Air”.
2. **Step 2.** Choose the mode with the round switch between the buttons. Teal: tap “Air” to turn on and once more to turn off. Background color: works only while you hold the button.
3. **Step 3.** Move the phone. Buttons: “Click”, “Center”, “Sensitivity”.

Limits: if the phone has no gyroscope you see “No gyroscope in this phone”.

## PC mouse

What it is: the phone becomes a Bluetooth mouse and keyboard for a computer, laptop, tablet, iPhone or TV (Android 9+). Nothing is installed on the computer.

1. **Step 1.** More → “Mouse”. If asked, allow access to nearby devices.
2. **Step 2.** Tap “Make visible (2 min)”. On screen: “Ready: waiting for a computer”.
3. **Step 3.** On the computer add a Bluetooth device and choose this phone.
4. **Step 4.** After that it connects by itself, or tap the computer in the “Paired devices” list. On screen: “Connected: name”.
5. **Step 5.** Control: drag a finger (cursor), tap (click), hold (right click). Buttons “Left”, “Middle”, “Right”. Text is typed in Latin letters.

For an Android device there is “Connect automatically”: choose the device, Bluetooth turns on there and the devices pair by themselves.

Limits: on some phones the HID profile is turned off by the firmware: then “This phone does not support Bluetooth HID (mouse/keyboard mode).”

## Gamepad

What it is: the phone as a Bluetooth gamepad for a PC, box, TV box or phone, and an on-screen gamepad over any game.

1. **Step 1.** More → “Gamepad”. Pair the phone with the device once (as for “PC mouse”), then tap the device in the list.
2. **Step 2.** Tap “Play”: sticks and buttons open. Layout: “Xbox (A, B, X, Y)” or “PlayStation”. Settings: “Dead zone”, “Size”, “Opacity”, “Vibration on press”, “Tilt of the phone replaces a stick”, profiles.
3. **Step 3.** The “Cursor” mode controls a cursor (“Cursor speed”); the “Game” mode shows sticks and buttons.
4. **Step 4.** “Play on this phone (prototype)” shows the gamepad over a game on this same phone. It needs ADB connected to this phone, Android 12+ and the permission “Draw over other apps”.
5. **Step 5.** The button “Test” opens a test: press a button on the gamepad and “Last button” appears.

Hint: for games with vibration and an Xbox controller on a PC, install “ADB-phon Bridge” on the computer. Many hosts do not send vibration to an unknown gamepad.

## SSH

What it is: connecting to a computer or server over SSH (Linux, macOS, Windows with an SSH server): terminal and files over SFTP.

1. **Step 1.** More → “SSH”.
2. **Step 2.** Enter “Address (IP or name)”, “User”, “Port” and “Password or key passphrase”. To sign in with a key tap “Key file…” and choose a file: “Key: name” appears. To remove it: “Remove key”.
3. **Step 3.** Tap “Connect”. On screen: “Connecting...”, then the tabs “Terminal” and “Files”. The server key is remembered on first connection.
4. **Step 4.** “Terminal”: enter a command and tap “Run”. Each command runs separately; the current folder is remembered.
5. **Step 5.** “Files”: browse folders, “Download”, “Upload here”, “Delete”.
6. **Step 6.** To leave: “Disconnect”.

If you see “The server key differs from the one remembered earlier”, do not connect unless you are sure the server was reinstalled: then tap “Forget server key” and connect again.

## Files

What it is: a two-pane file manager. Phone storage, card, USB drives, archives, Android/data over ADB, network folders and cloud drives in one tree.

1. **Step 1.** Files. If asked, tap “Allow access to files”.
2. **Step 2.** The root screen (“home”): groups “Storage”, “Network”, “Cloud drives”, “Devices”. A switch “Tree” / “List”.
3. **Step 3.** Actions: select files (long press), “Copy”, “Move”, “Rename”, “Delete”, “Duplicate”, “To archive”, “Extract here”, “Properties”, “Permissions”. Select by mask (for example *.jpg), search, bookmarks, history, sorting.
4. **Step 4.** Copying between panes: open the target folder in the other pane. You can also drag: hold a file and drag it onto a folder or a pane.
5. **Step 5.** If a file already exists, the window “File already exists” opens: “Replace”, “Skip”, “Rename”, “Apply to all”.
6. **Step 6.** Trash: what is deleted on the phone and the card first goes to a hidden folder .adbphon-trash. Settings: “File manager trash” (keep 7, 30, 90 days or no limit), “Delete confirmation”.
7. **Step 7.** An APK is opened with “Install”. Photos, video and music open in the built-in viewer and players.
8. **Step 8.** Side panel buttons: a long press on a button starts reordering, then tap another button and they swap places. On a remote: hold OK on a button, the arrows move it, OK confirms, Back cancels.
9. **Step 9.** The list position and focus are remembered: after leaving and coming back the selected folder is visible at once.

Limits: over ADB without root /system, Android/data and /data/local/tmp are available. The /data folder and the rights of other apps’ files are closed by Android itself. With root everything opens. A system folder cannot be changed (“System folder: operation not allowed”).

## Network folders

What it is: SMB, SFTP, FTP, FTPS and WebDAV: browsing, copying and moving in both directions, including between two network sources.

1. **Step 1.** Files → group “Network” → “Add connection…”.
2. **Step 2.** Choose the type. Enter “Address or name of the computer” (for WebDAV an address like https://nas:5006/dav/), “User”, “Password”, and if needed “Port” and a folder.
3. **Step 3.** Tap “Check connection”. On screen: “Connection works”.
4. **Step 4.** Tap “Save to favorites”. The connection appears in the list “Favorites”.
5. **Step 5.** You can search the network with “Find on network”.

The password is stored only on this phone, encrypted (key in Android Keystore).

Limits: if an SMB server does not let you in without a password, turn on the “Guest” account on that computer and give the folder rights for “Everyone”. On a version error choose another “SMB version”. FTPS: browsing and download are tested, upload only partly.

## Cloud drives

What it is: Google Drive, MEGA, Dropbox, OneDrive, Yandex Disk, Box, pCloud and dozens of other services in the common Files tree. The internet is required.

### How to sign in to a cloud

1. **Step 1.** Files → group “Cloud drives” → “Add cloud…”. On screen: the window “Add cloud”.
2. **Step 2.** The first time the program offers the “ADB-phon Clouds” module (the size is shown in the window): tap “Download and install”. On screen: “Downloading…”, “Checking the file…”, “Installing…”, then “Cloud module installed”. Installing needs the permission to install apps.
3. **Step 3.** Choose a service. There is “Search by name” and the section “All other clouds”.
4. **Step 4.** Tap “Sign in via browser”. The cloud’s sign-in page opens in the browser. The login and password are typed only there; the program does not see them. On screen: “Sign in in the browser, then return to the app.”
5. **Step 5.** After signing in the program returns by itself. For this to work, allow “Draw over other apps” (button “Turn on automatic return”) and notifications. On screen: “Cloud added”.
6. **Step 6.** Check: the cloud appeared in the group “Cloud drives”. The name can be changed (“Cloud name”).

If the sign-in page does not open or the next step is not what you expect: tap “Copy link” and open it in Chrome, or tap “Another browser”, or “Open the sign-in page again”. Sign-in continues while the program is minimized.

### MEGA and services with an app password

1. **Step 1.** MEGA: enter “E-mail MEGA” and “Password”, tap “Check and add”. MEGA has no browser sign-in. Two-factor protection must be off.
2. **Step 2.** Yandex Disk (app password): sign in with the Yandex login and an app password, not the usual password. Where to get it: Yandex ID → Security → App passwords.

### Own key (when the shared key has run out)

For Google Drive the program’s shared trial key is meant for the first users. If you see “The shared trial key has run out. Create your own, once.” or an error with the line “Tap here and connect your own key”, run the “Own key” wizard:

1. **Step 1.** The wizard shows “Step N of M”. Tap “Open the page” on every step: the site opens in the browser.
2. **Step 2.** Google: create a project (the name ADB-phon is already copied), tap “Enable” at Google Drive API, on the consent screen choose “External” and enter your email, publish the app (production, otherwise sign-in keeps dropping), create a “Desktop app” key and copy both values.
3. **Step 3.** The “app not verified” warning is normal: continue.
4. **Step 4.** The program picks up the copied values itself. On screen: “Both values found, signing in.” For other services the wizard is shorter (3 steps: create an app, set the return address, copy the key and secret).

Cloud trash: “Trash…”. Deleted files stay in the cloud’s trash (in some clouds in a hidden folder .adbphon-trash); from there they can be restored with the arrow or deleted forever.

Limits: if clouds are not supported on the device, use WebDAV. RAR5 and password-protected archives are supported.

## Wi-Fi server and Wi-Fi explorer

What it is: a phone folder opens by a link in the browser of any device on the same Wi-Fi network; files can be downloaded and uploaded. “Wi-Fi explorer” looks for such devices with ADB-phon.

1. **Step 1.** Files → “Wi-Fi explorer” → the row “Server” → “Start”. On screen: “The server is running. Open the link…” and a link with a key.
2. **Step 2.** Open the link in the browser of a phone, computer or TV on the same network. For ADB-phon on another device: Files → Network → WebDAV, paste the link (the WebDAV password is shown in the window, any login).
3. **Step 3.** Without a router: “Create own Wi-Fi network (no router)”. Connect the second device to the shown network with the password.
4. **Step 4.** On the second device with ADB-phon: “Wi-Fi explorer” → “Add device”.

Limits: anyone who knows the link and is on the network sees the folder. The server works while the program is open.

## Phone

What it is: information and control of the device. More → “Phone”. An ADB connection is required.

- “Device information” and monitoring.
- “Quick settings”: dark theme, Wi-Fi, mobile data, Bluetooth, auto-rotate, font size, screen timeout, touch display, animations, DPI.
- Screenshot and screen recording, clearing the cache of all apps.
- “Restart”: normal, recovery, bootloader.

## Console

What it is: a live terminal with any shell commands. More → “Console”.

1. **Step 1.** Connect ADB or root. On screen: “Commands run through ADB” (or through root).
2. **Step 2.** Type a command in the “Command:” field and tap “Run”. History, Ctrl+C and Tab work.
3. **Step 3.** To save a command: enter “New command name” and “Save”. It appears in “My commands” and runs with one tap.
4. **Step 4.** “Restart session” after the error “No session”.

Limits: when working through the helper (without debugging) a command runs one line at a time, and Ctrl+C, Tab and Esc are not available.

## Connection over the internet and Tailscale

What it is: connecting two devices to each other through a Tailscale network so that they see each other not only on one Wi-Fi network. Every installation has a “Program code” (12 characters).

### How to get an invitation on Android

The invitation is sent from a Windows computer (the “Phone in Window with Sound” program 4.0).

1. **Step 1.** On Android: Link → card “Connection over the internet”. On screen: the line “Program code: XXXX-XXXX-XXXX” and the button “Copy”. Give the code to the person who connects you (by voice or any way, once).
2. **Step 2.** On the PC: the launch panel “Phone in Window with Sound” → “Connection over the internet” → “Give a person access…”.
3. **Step 3.** Once on the PC: on the first send, “Permission for invitations” opens. Tap “Open the Tailscale keys page”, sign in to your account, tap “Generate access token…” (90 days), copy the long code (it starts with tskey-api-), paste it and tap “Check and save”. On screen: “Account found: …, devices: N. Permission saved.”
4. **Step 4.** Tap “Add a person…”, enter a name and their “Program code” (12 characters), tap “Save”. If the person is already in your Tailscale network, pick their device: the invitation goes directly.
5. **Step 5.** Choose the person and tap “Send the invitation”. On the PC screen: “The invitation was delivered directly over Tailscale…” or “The invitation was sent over a secure channel…” (on the phone it appears within half a minute).
6. **Step 6.** Fallback: “Fallback: copy the text”. Send the text to the person any way you like. On Android tap “I have an invitation…” and paste the invitation text (tvo://join?d=…).
7. **Step 7.** On Android the notification “Invitation received” appears. Open ADB-phon: the window “Invitation”. Tap “Accept and connect”.
8. **Step 8.** If another VPN is running on the phone, the window “Another VPN” appears. Tap “Turn off for the session”. This needs an ADB connection to this phone (if there is none, connect on the Link tab and tap “Accept” again). After the session the previous VPN returns by itself; the shade has the notification “Internet connection session” and the button “Restore now”. Auto-end: “End session after idle, min”.
9. **Step 9.** Progress on screen: “Downloading Tailscale (about 100 MB)…” → “Installing Tailscale…” → “Signing in with the key…”. Check: “Done: you are connected with “name””. On the PC the device appears in the list of the “Connection over the internet” window.
10. **Step 10.** If automatic sign-in did not work, the key is copied for a minute: in the Tailscale app choose “Use an auth key” (or paste the key into the field) and confirm.

Your own device: install Tailscale and sign in to your account; no invitation is needed.

### How to connect by a 100.x address

1. **Step 1.** Both devices must be in the same Tailscale network (see above). An address like 100.x.x.x is visible in the Tailscale app and in the “Connection over the internet” window on the PC.
2. **Step 2.** Files → “Devices” → “Add device” → “Android: phone, tablet, set-top box”.
3. **Step 3.** Enter the 100.x.x.x address (the default ADB port is 5555; for “Wireless debugging” enter its port). Optionally add a “Second address” (for example 192.168.x.x).
4. **Step 4.** On the device turn on network debugging. On first connection it asks “Allow debugging?”: tick “Always”.
5. **Step 5.** The device menu has “Connect via Tailscale”.

If you get the error “this phone is not in the Tailscale network now”: Tailscale is off, or another VPN (for example OpenVPN) occupies Android’s single VPN slot. Turn the other VPN off, turn Tailscale on and try again.

Limits: Android allows one VPN at a time. A permanent other VPN gets in the way of Tailscale. An invitation cannot be sent from the phone yet, only from the PC.

## Devices and Video window

What it is: in the Files tree there is one group “Devices”: computers and Android devices together. Each has two folders: “Files” and “Video window”. “Video window” shows the device screen and controls it.

1. **Step 1.** Files → group “Devices” → “Add device”. Choose the type: “Windows PC” or “Android: phone, tablet, set-top box”. Enter the address.
2. **Step 2.** On the Android device turn on network debugging (ADB, port 5555) or “Wireless debugging”. On first connection tick “Always”.
3. **Step 3.** Open the device → “Video window”. On screen: the image of the device screen; you can control it.
4. **Step 4.** Text input: the field “Text (any layout: Cyrillic, Latin, emoji)”.
5. **Step 5.** Auto-rotation: when the screen rotates, the picture rebuilds by itself.

Limits: for text input in any language the target device needs ADB-phon 7.0 or newer and a text field with a cursor (otherwise “The text was not typed”). If the device screen is off you see “Device screen is off”.

## PC screen

What it is: viewing and controlling the screen of a Windows PC over Tailscale. More → “PC screen”.

1. **Step 1.** On the PC, in the “Phone in Window with Sound” program, turn on “Access to this screen” (tray icon menu).
2. **Step 2.** Tailscale must be running on the phone (see “Connection over the internet and Tailscale”).
3. **Step 3.** In the PC’s “Connection over the internet” window look at the PC address (100.x.x.x) and the “Program code”.
4. **Step 4.** In ADB-phon enter “PC address in Tailscale (100.x.x.x)”, “PC program code (12 characters)”, “Name of this phone” and tap “Connect”.
5. **Step 5.** On screen: “Waiting for permission on the PC…”. On first connection the PC asks for permission: allow it. Then the PC screen appears.
6. **Step 6.** Control: “Quality” (“Auto”, “Fast”, “Sharp”), “Monitor”, “Keys”, “PC clipboard”, the field “Text to type on the PC” and “Send”. To leave: “Disconnect”. “Forget the PC” deletes the saved address and code.

Limits: if you see “No screen service at this address.” or “Access was denied on the PC.”, check steps 1 and 3. If another VPN occupies the VPN slot, turn it off (Settings → Network → VPN).

## Video call

What it is: a video call between Android devices and computers (the Windows program “Phone in Window with Sound” 4.0). The call goes directly between the devices; no browser is needed.

### How to call

1. **Step 1. Exchange codes.** Find out the other person’s “Program code”: on Windows it is in the “Connection over the internet” window, on Android on the Link tab in the card “Connection over the internet”. Give them your code. This is done once, by voice or any way.
2. **Step 2.** On Android: Link → card “Video calls” (below “Connection over the internet”). In the field “Name shown in calls” enter how you will be shown.
3. **Step 3.** Tap “Add a person…”. Enter “Name” and “Program code (XXXX-XXXX-XXXX)”, tap “Add”. If the code is not 12 letters and digits you see “The code must have 12 letters and digits.” The person appears in the list.
4. **Step 4.** Tap “Call” next to the name. The cross on the right removes the person from the list.
5. **Step 5.** On the other person’s screen the window “Calling…” appears with the buttons “Answer” and “Decline”. The camera and microphone turn on only when they tap “Answer”.
6. **Step 6.** During the call the screen has the buttons: “Sound” (on/off), “Speaker” (on/off), “Camera” (on/off) and “End call”.

From Windows: in the “Video call” window choose a person (if needed “Add a person…”) and tap “Call”. The button “Check camera and microphone” tests them only on your side, sending nothing.

### Squeal and echo in a call

Causes: another program sends sound or the microphone (for example AnyDesk, TeamViewer, Zoom), so the sound doubles and loops; a speaker stands next to the microphone.

1. Turn off sound and microphone sending in the other program (in AnyDesk: the audio input device).
2. Put on headphones.
3. In the call window tap “Sound” or “Speaker” to switch off what is extra.

### Receiving calls

Receiving calls is on by default in ADB-phon 7.0: a small icon “Receiving video calls” sits in the notification shade. The switch “Receive calls in the background (a small icon stays in the notification shade)” is in the same card “Video calls”. If a call is not answered: “Call cancelled” or “Missed call”.

Limits: the other person must have the program running (on Windows an icon in the tray) and call receiving on. If the call did not start you see “The call did not start.” If another call is in progress: “A call is already in progress.” A connection over the internet between the devices is needed (a common network or Tailscale).

### The call window, the small window and “Hide, the call goes on”

During a call the whole window scrolls: “End call” stays pinned at the top, below it the main buttons, then the level meters and “More”; on a wide screen the picture and one column with the buttons stand side by side. Rarely needed items are under “More”: mirrored self view, rotation, view, video and audio recording, screenshot, quality, microphone and speaker choice.

- “Full screen” removes all buttons. “Back” brings the buttons back, the call does not end.
- “Small window” (and the “Home” button) turn the call into a small window over the screen: picture in picture. It has buttons: end the call, record, screenshot (the system gives a small window up to three buttons; the system shows “Expand” itself). Ending from the small window stops the recording.
- “Hide, the call goes on” closes the window completely: the call and the sound go on, you can come back from the notification.

### Ringtones and the ring-back tone

Connection → the “Video calls” card → “Ringtone…”. Separately for an incoming video call and a voice call: 8 built-in ringtones, “Your own file…”, “Silent”, volume, “▶ Preview”. The ring-back tone is heard only by the caller.

### Avatar

The “Video calls” card → “My avatar”: “Choose” (the picture is cropped to a square at the center and shrunk to 128×128) or “Remove”. The avatar is shown to the other side instead of a letter when they have no video. The “Picture instead of video” switch (for yourself only): “Contact’s avatar”, “Your own picture”, “Letter only”.

### A call and the window from the device list

In “Files” → the “Devices” group every device has the rows “Video call”, “Audio call”, “Device window” and “Files” (the same in the long-press menu). If the device has no “Program code” yet, the program asks for it.

## Shizuku

What it is: ADB-phon starts a server compatible with Shizuku. Apps that support Shizuku get shell rights. You do not need to install Shizuku separately.

1. **Step 1.** Connect ADB or root (the Link tab).
2. **Step 2.** Options → the block “Shizuku (shell rights for other apps)” → “Start”. On screen: the status “Running: …” and “Shizuku server started.”
3. **Step 3.** Open an app that supports Shizuku and request access. In ADB-phon the window “Shell rights access” appears: “Always allow”, “Only now” or “Deny”. Allow only apps you trust.
4. **Step 4.** The list of allowed apps: “Apps with access…”. Apps appear there when they request Shizuku.
5. **Step 5.** “Start after reboot”: the server starts by itself after a reboot if it was started before (root or Wi-Fi debugging on Android 11+).

Limits: without an ADB connection you see “No ADB connection: connect on the Connect tab.” If the server did not answer, check ADB and try again.

## Settings

The Options tab.

- **Theme and colors.** Theme: auto, light, dark. “Wallpaper colors (Material You)” (Android 12+) takes colors from the wallpaper. “Main color”: see “Color settings” below.
- **Font size and element size.** Changed here too.
- **“Full screen” mode.** Turned on here too.
- **Color settings.** “Main color”: a color bar, and the button “Like the theme” to the right of the title returns the theme color. “Bar color”: one color bar for the used part on all bars (memory, card, root, clouds, progress of work and installation), with “Like the theme” to the right of the title. “Bar contrast”: a slider from “weak” (the previous look) to “strong” (the free part is fully transparent), with an outline around the whole contour of every bar. On a remote: left and right arrows move the color, the up arrow goes to “Like the theme”, OK resets the color.
- **Scrolling of long names.** “On” (the name of the selected item scrolls) or “Off”.
- **Language.** Russian, Ukrainian, English (“Language”).
- **Custom icons.** For folders (“Custom folder icon…”), file types (“File type icons…”), drives, apps, tabs and side panel buttons. Tap an icon or “Change icon…”, then “From file…”. To undo: “Reset”. The side panel buttons of Files are removed and restored in “Side panel buttons…” (the button “Default” returns everything).
- **Launch.** “Open this tab at launch” (the last one or any) and the folder in Files at launch.
- **Screen.** “Keep the screen on while the app is open” (off by default).
- **Back button.** It can close the app completely when there is no connection.
- **Log for finding errors.** Turned on with a switch.
- **Backup.** “Create backup…”: settings, device lists, connections, cloud drives with sign-in, passwords and the ADB key. A passphrase (optional, at least 6 characters) encrypts the file. Without a phrase the file contains plain passwords: keep it in a safe place. “Restore…” brings a backup back, “Undo the last restore” undoes it. “Automatic backup (full)”: off, daily, weekly. After restoring tap “Restart”.
- **Settings backup.** “ADB-phon settings backup…”: the backup folder is chosen once.
- **Permission check.** “Check permissions”.
- **Shizuku.** See the section “Shizuku”.

## Updates

1. **Step 1.** Options → “Check for updates”.
2. **Step 2.** If a new version is out, the window “A new version is out” appears: “Update” or “Later”.
3. **Step 3.** The automatic check once a day is turned on with the switch “Check for updates once a day” (off by default).
4. **Step 4.** Any version of the program (including an old one) can be installed through “Versions / roll back…”.

The program version is shown in the settings.
