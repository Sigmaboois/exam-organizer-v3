# Exam Organizer for iPad

The iPad (and iPhone) version of Exam Organizer, written in SwiftUI and built with
[xtool](https://github.com/xtool-org/xtool), so it can be built on Windows without a Mac.

It supports paper organisation, checking and class records:

| Screen | What it does |
|---|---|
| **Organise** | Drag Cambridge PDFs in (or pick files and folders), check what was detected, fix anything by hand, and sort them into `Subject › Qualification › Code › Year › Session › Paper › Variant`. Sorted exams show up in the Files app under *On My iPad › Exam Organizer*, or in any folder you choose (iCloud Drive works). |
| **Check papers** | Add students' solved papers (exported from Notability, GoodNotes…, or shared straight to the app). It spots which pages have answers, downloads the mark scheme and grade thresholds, marks every question with Gemini, and makes a marked PDF. Every mark can be changed. |
| **Classes** | Classes and students (type them or paste a list), each class's average, grades and topics to revise, and a spreadsheet export. |

## Building it (Windows)

One-time setup, from xtool's [Linux/Windows guide](https://github.com/xtool-org/xtool/blob/main/Documentation/xtool.docc/Installation-Linux.md):

1. **WSL**: in an admin PowerShell run `wsl --install`, then restart.
2. **Apple Devices** from the Microsoft Store (Apple's iPhone service for Windows).
3. In WSL (Ubuntu):
   - install the Swift toolchain from <https://swift.org/install/linux>, plus `build-essential`
   - `sudo apt-get install usbmuxd libimobiledevice-utils rsync zip`
   - download `xtool` from its [latest release](https://github.com/xtool-org/xtool/releases/latest) and put it on your `PATH`
4. Download **Xcode** (`Xcode.xip`) from <https://developer.apple.com/download/all/?q=Xcode> with your Apple ID.
5. Run `xtool setup`: log in with your Apple ID and give it the path to `Xcode.xip`.

### Installing on the iPhone / iPad

Plug the device in, unlock it, then double-click **`install-iphone.cmd`**. It checks everything step by
step, builds the app, installs it, and saves a log in `ios/logs/`. If it asks to revoke a certificate,
type `yes`.

What it relies on (all set up once):
- **Apple Devices** (Microsoft Store): Apple's iPhone service on Windows. The device connects through it,
  because USB pass-through to WSL (usbipd) breaks on large transfers.
- `%USERPROFILE%\.wslconfig` with `networkingMode=VirtioProxy` under `[wsl2]`, so WSL reaches both the
  internet and that service. Turn off any VPN (Kaspersky VPN) while installing.
- Developer Mode on the device (*Settings › Privacy & Security › Developer Mode*).

The first time, trust the developer on the device: *Settings › General › VPN & Device Management*.

### The Gemini key

`Sources/ExamOrganizer/Secrets.swift` holds the key built into the app. It's gitignored because
this repo is public. **Never commit it.** With an empty key the app still works, and teachers add
their own free key in Settings.

### Free Apple ID limits

With a free Apple ID the app works for 7 days, then needs `xtool dev` again. To give it to
other teachers properly (TestFlight or the App Store) you need Apple's paid developer account.

### Developer Mode setup

All launchers now use the same install flow, including `install-iphone-ready-v2.ps1`.
If Developer Mode is disabled or hidden, the installer requests `idevicedevmodectl reveal`
for the connected device and continues to the normal xtool development install attempt.
On the device, open **Settings > Privacy & Security > Developer Mode**, enable it,
restart, unlock and confirm **Enable**. Then run `install-iphone.cmd` again if needed.
This exposes the setting when supported; it does not bypass the device confirmation.
