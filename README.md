# ktop

ktop is an extension for Hammerhead Karoo bike computers that brings
**Wheeltop EDS** electronic groupsets (EDS OX, OX2, TX, GeX) to Karoo:
live gear, shift counts and battery levels as Karoo data fields, plus a
companion app to manage the groupset.

> **Status: early test version (0.x).** Things may break. Please report
> problems with a logcat (see below).

Inspired by and partly based on [Ki2](https://github.com/valterc/ki2) by
Valter C. (MIT License) — the data fields, graphical views and app layout
follow Ki2 as closely as possible.

## Screenshots

| Ride page: KI2-style data fields | App: device details |
|:---:|:---:|
| <img src="docs/media/ride-fields.gif" width="300" alt="ktop data fields on a Karoo ride page while shifting"> | <img src="docs/media/app-device-details.gif" width="300" alt="ktop app showing device details, race and sleep mode"> |
| [video (mp4)](docs/media/ride-fields.mp4) | [video (mp4)](docs/media/app-device-details.mp4) |

| Data fields | App settings |
|:---:|:---:|
| <img src="docs/media/ride-fields.png" width="300" alt="Gears, Shifting Battery, Shift Count, Gear Ratio, Drivetrain and Gears Detailed fields"> | <img src="docs/media/app-settings.png" width="300" alt="ktop app settings tab"> |

## Features

- Connects to the Wheeltop EDS rear derailleur over Bluetooth LE and keeps
  the connection shared between Karoo and the app.
- Karoo data fields (same set as Ki2):
  - Text: Rear Gear, Front Gear, Gears (`01-06`), Gears T, Rear/Front
    Gear T, Gear Ratio, Shift Count, F/R Shift Count, Shifting Battery %
  - Graphical: Gears Index / Size, Gears Detailed, Drivetrain Index / Size,
    Bike Battery
  - Additionally: battery voltage of rear derailleur, front derailleur and
    both shifters
- Companion app:
  - Manage several Wheeltop groupsets (add by scan, rename, reorder, remove)
  - Device details: live drivetrain view, gears, batteries (RD, FD, left
    and right shifter), firmware versions
  - **Race mode** and **sleep mode** switches
  - Custom gearing (teeth counts) per device for Gears T / Gear Ratio
  - Battery warning levels

## Requirements

- Hammerhead Karoo with extension support (karoo-ext 1.1.x)
- Wheeltop EDS groupset (OX, OX2, TX or GeX)

## Installation

1. Download `ktop-<version>.apk` from the
   [latest release](../../releases).
2. Sideload it to the Karoo
   ([Hammerhead: Companion App Sideloading](https://support.hammerhead.io/hc/en-us/articles/31576497036827-Companion-App-Sideloading)).
3. Open **ktop** on the Karoo once and allow Bluetooth access.
4. Wake the groupset (shift once or move the bike), press **+** in the
   *Devices* tab and **Add** your groupset.
5. In Karoo: **Sensors → + → Extensions → Wheeltop (EDS)** and add the
   groupset.
6. Add the ktop data fields to your ride profile pages.

Optional: open the device in ktop → **Gearing** and enter the chainring
and cassette teeth (Wheeltop EDS does not report teeth counts). Needed for
Gears T, Gear Ratio and the "Size" views.

## Known limitations

- Teeth counts must be entered manually (see above).
- Battery percentages are estimated from voltage (linear approximation).
- Front derailleur position (2x, EDS TX) is implemented from the protocol
  documentation but not yet tested on a real 2x system.
- Ki2's "Shifting Mode" field is Shimano-specific and not available.
- Fields that were added to a profile with an older ktop version may need
  to be removed and added again.

## Reporting problems

Please open an issue and attach a logcat filtered by `ktop-ble`
(Android Studio *Logcat* or `adb logcat -s ktop-ble`), plus your groupset
model and what you did.

## Building

- Android Studio (JDK 17+).
- karoo-ext is fetched from GitHub Packages: add a GitHub token with
  `read:packages` to `~/.gradle/gradle.properties`:
  ```
  gpr.user=<github user>
  gpr.key=<token>
  ```
- Release signing (optional) via `local.properties`:
  ```
  RELEASE_STORE_FILE=/path/to/keystore.jks
  RELEASE_STORE_PASSWORD=...
  RELEASE_KEY_ALIAS=...
  RELEASE_KEY_PASSWORD=...
  ```

### Releases (GitHub Actions)

Pushing a tag such as `0.1.0` (or `0.1.0-preview.1`) builds a signed
release APK and attaches it to a GitHub release. Repository secrets
needed:

| Secret | Content |
|---|---|
| `ANDROID_RELEASE_KEY_BASE64` | release keystore, base64 encoded |
| `ANDROID_RELEASE_KEY_ALIAS` | key alias |
| `ANDROID_RELEASE_KEY_PASSWORD` | keystore and key password |
| `GPR_USER` | GitHub user for karoo-ext |
| `GPR_KEY` | GitHub token with `read:packages` |

## Credits

- [Ki2](https://github.com/valterc/ki2) by Valter C. — data field set,
  views (GearsView, DrivetrainView, BatteryView, FillView), layouts, app
  structure. MIT License, see `LICENSE-ki2.txt`.
- [eds-ox](https://git.jeckyll.net/published/personal/eds-ox) — reverse
  engineered Wheeltop EDS Bluetooth protocol and reference app.
- [karoo-ext](https://github.com/hammerheadnav/karoo-ext) by Hammerhead.

Wheeltop and Karoo are trademarks of their respective owners. This project
is not affiliated with Wheeltop or Hammerhead.

## License

MIT — see `LICENSE`.
