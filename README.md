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
  the connection shared between Karoo and the app. Reconnects
  automatically (every 10–60 s) if the groupset was asleep at ride start
  or the link drops.
- Karoo data fields (same set as Ki2):
  - Text: Rear Gear, Front Gear, Gears (`01-06`), Gears T, Rear/Front
    Gear T, Gear Ratio, Shift Count, F/R Shift Count, Shifting Battery %
  - Graphical: Gears Index / Size, Gears Detailed, Drivetrain Index / Size,
    Bike Battery
  - Additionally: battery voltage of rear derailleur, front derailleur and
    both shifters
- Native Karoo shifting sensor (like Ki2): Karoo's own gear fields work and
  gear changes are recorded in the FIT file
- Extra data in the FIT file (developer fields, see below)
- **Overlay** (like Ki2): gear pop-up on top of every ride page, also the
  map. Shows gear, teeth/ratio (with custom gearing), cassette bar and RD
  battery; configurable display time, position, theme and opacity. Needs
  "Display over other apps" for ktop (the Settings tab asks for it).
- **Sounds** (like Ki2): Karoo beep when reaching the easiest or hardest
  gear (single/double beep, Karoo signal, bell)
- **Low battery alert** during the ride for the shifter coin cells and the
  front derailleur (Karoo itself warns about the rear derailleur)
- Companion app:
  - Manage several Wheeltop groupsets (add by scan, rename, reorder, remove)
  - Device details: live drivetrain view, gears, batteries (RD, FD, left
    and right shifter), firmware versions, shifter button assignment
    (read-only)
  - **Race mode** and **sleep mode** switches
  - Custom gearing (teeth counts) per device for Gears T / Gear Ratio:
    cassette presets (Shimano, SRAM, Campagnolo, Rotor, SUNSHINE-SZ, GOLDIX,
    LTWOO, 10–13 speed) or
    enter any cassette as a list (up to 14 cogs)
  - Settings: battery levels and alerts, overlay, sounds
- English and German (follows the Karoo language setting)

## Requirements

- Hammerhead Karoo with extension support (karoo-ext 1.1.9)
- Wheeltop EDS groupset (OX, OX2, TX or GeX)

## Installation

1. Download `ktop.apk` from the
   [latest release](../../releases/latest).
2. Sideload it to the Karoo
   ([Hammerhead: Companion App Sideloading](https://support.hammerhead.io/hc/en-us/articles/31576497036827-Companion-App-Sideloading)),
   e.g. by sharing this link with the Hammerhead Companion app:
   `https://github.com/geowiwi/ktop/releases/latest/download/ktop.apk`
3. Open **ktop** on the Karoo once and allow Bluetooth access.
4. Wake the groupset (shift once or move the bike), press **+** in the
   *Devices* tab and **Add** your groupset.
5. In Karoo: **Sensors → + → Extensions → Wheeltop (EDS)** and add the
   groupset.
6. Add the ktop data fields to your ride profile pages.
7. Optional: ktop → *Settings* → **Overlay** / **Sounds**. Turning the
   overlay on opens Android's "Display over other apps" screen for ktop.

Updates: Karoo checks GitHub for new ktop releases and offers them for
installation (pre-releases are not offered).

Optional: open the device in ktop → **Gearing**, enter the chainring and
pick your cassette (**Choose cassette**) or type it in (**Enter as list**,
e.g. `11-13-15-17-19-21-23-25-28-32-36-40-46`). Wheeltop EDS does not report
teeth counts. Needed for Gears T, Gear Ratio, the "Size" views and the
teeth in the overlay.

## FIT file

- Gear changes: standard FIT gear-change events, written by Karoo
- Every minute: `ktop_rd_battery_v`, `ktop_fd_battery_v` (2x),
  `ktop_left_shifter_v`, `ktop_right_shifter_v`, `ktop_rd_battery` (%)
- Per ride (session): `ktop_shifts`, `ktop_front_shifts`, `ktop_rear_shifts`,
  `ktop_rd_battery_start`, `ktop_rd_battery_end`, `ktop_race_mode`
- Developer fields show up in e.g. intervals.icu, GoldenCheetah and FIT
  viewers, not in Strava

## Battery and runtime

- RD battery about 800 mAh; roughly 10,000–20,000 shifts per charge
- Runtime ≈ shifts per charge ÷ your shifts per hour (about 20–45 h)
- ktop keeps the RD awake while connected; batteries read every 30 s
- Race mode and sleep mode off use more energy
- Battery % is estimated from voltage (RD/FD 6.6–8.2 V, shifters 2.0–3.2 V)
- Measure your own: Shifting Battery % and Shift Count at start and end of a ride

## Known limitations

- Teeth counts must be entered manually or picked from the presets (see
  above). No 14-speed preset yet (no verified sprocket sequence); use
  "Enter as list".
- Battery percentages are estimated from voltage (linear approximation).
- Front derailleur position (2x, EDS TX) is implemented from the protocol
  documentation but not yet tested on a real 2x system.
- Ki2's "Shifting Mode" field is Shimano-specific and not available.
- Fields that were added to a profile with an older ktop version may need
  to be removed and added again.
- Native shifting / FIT gear events: if the groupset was paired in Karoo
  with ktop 0.1.2 or older, remove it under Sensors and add it again.
- If the groupset is also paired via ANT+ (newer Wheeltop firmware), Karoo
  has two shifting sources; keep only one of them enabled.
- Shifter buttons cannot be used as Karoo buttons (e.g. next page): the
  shifters talk only to the rear derailleur, which reports only the
  resulting cog position (serverReportGear), not the button. Both right
  buttons give identical frames, a long press looks like several single
  shifts, and pressing past the last cog sends nothing (logcat test,
  EDS GeX, 2026-10-17).
- Tested with EDS GeX firmware RD 2.67, shifters L 3.25 / R 4.30,
  ANT 6.2. Newer Wheeltop firmware (e.g. the August 2026 update) is not
  tested yet; please report your firmware versions (shown in the device
  details) with any problem.

## Roadmap / ideas

- Fine-tuning from the Karoo: test shifts, micro adjustment, cog positions
  (bike in a stand, backup and restore of values)
- Compare with native ANT+ support in newer Wheeltop firmware
- Test newer Wheeltop firmware

## Reporting problems

Please open an issue and attach a logcat filtered by `ktop-ble`
(Android Studio *Logcat* or `adb logcat -s ktop-ble`), plus your groupset
model and what you did. For raw protocol frames also include the
`ktop-raw` tag (`adb logcat -s ktop-ble ktop-raw`).

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
