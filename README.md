![Logo-icon](Common/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp)

# JugglucoNG

JugglucoNG is a continuous glucose monitoring app for Android, built around a Material 3 Expressive interface and a multi-sensor runtime. It began as a fork of [Juggluco](https://github.com/j-kaltes/Juggluco) by Jaap Korthals Altes and still shares a substantial Libre and native foundation with it. Today it is its own application rather than a UI layer over Juggluco: JugglucoNG has its own architecture, managed sensor drivers, sensor-independent data layer, alarm engine, journal and prediction tooling, and integrations.

![Screenshot](juggluco_screenshot.png)

<sub>Interface: English · Беларуская · 中文 · Deutsch · Français · Italiano · Nederlands · Polski · Português · Русский · Svenska · Soomaali · Türkçe · Українська · Монгол</sub>

**Latest alpha: [Releases](https://github.com/ctqvva/JugglucoNG/releases)**. Expect rough edges, and never make treatment decisions based on this app alone.

## Sensor and data-source support

Direct sensor families:

- Abbott FreeStyle Libre / 2 / 2+ / 3 / 3+
- Dexcom G7 / ONE+
- CareSens Air
- Accu-Chek SmartGuide

- Sibionics GS1 (EU, Chinese, and Hematonix), Sibionics 2
- AiDex X / LinX
- iCan i3 / i6 (Sinocare)
- Anytime / Yuwell
- MQ / Glutec
- Ottai / SyAi

Follower sources: Nightscout and the HTTP API.

Multiple direct sensors can run at the same time. An opt-in handover mode starts the next sensor automatically when the current one reaches its official expiry. Bluetooth fingerstick meters can log readings straight into the journal.

## Features

**Glucose display.** Material 3 dashboard with trend arrow, delta, and reading history; statistics (time in range, GMI, AGP percentile views, exportable reports) with persistent time ranges; charts in the notification shade; always-on display; floating overlay; home-screen widgets; automatic dark mode.

**Alarms.** Custom alarm engine: low/high with time ranges and episodes, rate-of-change alarms (falling/rising fast), sensor-expiry warnings at configurable thresholds, signal-loss and forecast alerts, follower alerts. Alarms can be spoken (TTS), delayed, or vibrate-first, and a failed delivery does not silently consume the alarm.

**Journal and insulin.** Food database with meal curves, dose calculator, insulin-on-board (IOB/eIOB) and carbs-on-board (COB) tracking, treatment intake from AAPS, treatment sync with Nightscout, quick-add entries.

**Prediction.** On-device predictive simulation of glucose, insulin, and meals, with configurable model profiles.

**Nightscout.** Upload readings and treatments, follow a remote site, and exchange IOB/eIOB/COB through devicestatus in both roles (opt-in). Long-acting insulin entries are supported.

**Data.** Sensor-independent local database with export/import, direct import of Juggluco's TSV export, settings export, and non-destructive calibration exports. Per-sensor calibration models with chart and table views.

**Sharing and integrations.** Mirror the app to another phone over LAN or the internet (ICE/TURN, no account needed); `glucodata`-style broadcasts for other apps and watchfaces; Health Connect; Pebble; an [outbound API](docs/outbound-api.md) that pushes readings to Telegram, VK, or any webhook.

## Building

Requirements: JDK 21, Android SDK with NDK and CMake, and the libjuice submodule:

```sh
git submodule update --init
./gradlew :Common:assembleMobileDebug --no-daemon
./gradlew :Common:assembleMobileRelease --no-daemon
```
The app is localised into 15 languages; new user-facing strings go into `Common/src/main/res/values/strings.xml` and every `values-*` locale.


## Version history

See the [Releases page](https://github.com/ctqvva/JugglucoNG/releases) for the changelog of each Alpha build.

## License and credits

GPL-3.0 (see [LICENSE.txt](LICENSE.txt)). Forked from [Juggluco](https://github.com/j-kaltes/Juggluco) by Jaap Korthals Altes, whose Libre and native work JugglucoNG still builds on. Developed by [ctqvva](https://github.com/ctqvva) with contributions from the community.

**Disclaimer:** this software is experimental and comes with no warranty of any kind. It is not a medical device. Always confirm readings with an approved device before acting on them.
