# FitCloud Watch Face & System UI Capability Report

Date: 2026-09-17
SDK: `com.topstep.wearkit:sdk-fitcloud:3.0.2.4` (AAR inspected: classes + assets + bytecode; usage ground-truthed against the official wiki https://github.com/htangsmart/FitCloudPro-SDK-Android/wiki/09.Dial and /08.Dfu)
Device: P6_ (MAC 90:EB:99:D6:5F:99), already bound via BlueBit

## 1. Watch-face APIs discovered (all `com.topstep.fitcloud.sdk.v2`)

Query / metadata:
- `FcSettingsFeature.requestDialPushInfo(): Single<FcDialPushInfo>` — shape, toolVersion, current dial num/binVersion/position, list of dial spaces
- `FcSettingsFeature.requestUiInfo(): Single<FcUiInfo>` — uiNum, uiSerial, styleIndex (active UI resource pack)
- `FcSettingsFeature.requestLcdShape(): Single<FcLcdShape>` — LCD id + `FcShape` (width/height/circle/rect/corners)
- Models: `model/settings/dial/FcDialPushInfo`, `FcDialSpace` (dialType, dialNum, binVersion, components, binFlag, spaceSize, isPushable), `FcDialComponent` (styleCurrent/styleCount, position, size), `FcShape`, `FcLcdShape`

Mutate:
- `FcDfuManager.start(DfuType.DIAL, Uri, binFlag, ...)` — push/replace a dial in a slot (the push activates the dial)
- `FcSettingsFeature.setDialComponent(position, ByteArray)` — switch component styles in a GUI dial (needs `DIAL_COMPONENT` + `SETTING_DIAL_COMPONENT`)
- `FcSettingsFeature.deleteDial(dialNum)` / `deleteDialByPosition(position)` — needs `DELETE_DIAL`

Custom dial generation:
- `utils/dial/DialWriter` — builds dial `.bin` from background bitmap + preview bitmap + style position; CRC16; `setCustomDialNum`, `setCopyFile`, auto-scale options
- `utils/dial/GUIWriter` — writes GUI-dial section (568x CRC variant present)
- `utils/dial/DialDrawer` / `DialView` — preview rendering, style color tinting (white/black/yellow/green/gray)
- AAR assets include base templates: `gui_dial.bin`, `gui_dial_368_448.bin`, `gui_dial_titan.bin`, `header.bin`, `header_tp.bin`

Related UI-surface APIs:
- `FcDfuManager.DfuType`: FIRMWARE, **UI**, DIAL, SPORT, GAME, GPS — UI/SPORT/GAME packs are flashable resource packages
- `FcSportUIAbility.requestSportUIEditor/setSportUIEditor` — pick among predefined workout-screen layouts (feature `SPORT_UI_EDITOR`)
- `FcSettingsFeature.setLockScreen(boolean, byte[])` — custom lock-screen image (feature `SCREEN_LOCK`)
- `FcSettingsFeature.setLyricColor(r,g,b)` — music lyric color
- `FcAlbumAbility` — push photos to on-watch album (feature `ALBUM`)
- `FcConfigFeature.setPageConfig` — which system pages/menus are enabled (visibility only, not appearance)

## 2. Answers to the 12 capability questions

| # | Capability | Status |
|---|---|---|
| 1 | Official watch-face selection | **Partial / NOT FOUND as API.** No public "select installed dial" call exists anywhere in the SDK (verified by full class-list search). Pushing a dial replaces+activates it; switching between already-installed faces is done on the watch itself. |
| 2 | Custom watch faces | **VERIFIED** — `DialWriter` + `FcDfuManager(DIAL)`; requires `DIAL_PUSH` (+`DIAL_CUSTOM`). |
| 3 | Watch-face installation | **VERIFIED** — `FcDfuManager.start(DfuType.DIAL, uri, binFlag)`. |
| 4 | Watch-face removal/replacement | **VERIFIED** — `deleteDial` / `deleteDialByPosition` (`DELETE_DIAL` flag); replacement = push over a pushable slot. |
| 5 | Watch-face data/configuration | **VERIFIED** — `requestDialPushInfo`, `setDialComponent` for GUI-dial component styles. |
| 6 | System-wide themes | **NOT FOUND** — no theme API/flag/resource. Only the whole-UI DFU pack exists (see #12). |
| 7 | System UI colours | **NOT FOUND**, except lyric color and dial-component style colors (dial-local). |
| 8 | Menu backgrounds | **NOT FOUND** — `FcPageConfig` toggles page visibility only. |
| 9 | Icons | **NOT FOUND**. |
| 10 | Notification appearance | **NOT FOUND for visuals** — `FcNotificationConfig` controls which app categories notify; content style is firmware-fixed. |
| 11 | Workout-screen appearance | **VERIFIED but limited** — SPORT UI pack push + `FcSportUIEditor` selection among predefined layouts; `SPORT_UI_EDITOR` feature. Not free-form styling. |
| 12 | Other firmware/UI resources | **VERIFIED** — DFU types UI/SPORT/GAME/GPS/FIRMWARE exist and are signature-checked by the device; official flow sources packs from the FitCloud server "Version Check". Treating UI packs as a theming mechanism is INFERRED, not documented for app-driven customization, and is firmware-adjacent (excluded by safety rules for now). |

## 3. Watch-face format (Phase 2)

- Container: dial `.bin` written by `DialWriter`; CRC16-validated (`check_crc16`, `check_crc16_568x` for 568x ICs).
- Two formats, negotiated by device: **Base** (one bin per style, 5 fixed styles) and **GUI** (`GUI` feature; single bin, multiple components, styles switched via `setDialComponent`).
- Background/preview: Android `Bitmap`, scaled to device `FcShape` from `requestDialPushInfo`/`requestLcdShape` — dimensions are device-queried, not hard-coded. `gui_dial_368_448.bin` implies 368x448 is one known panel; P6_ value must be read from the device.
- Supported data fields/widgets: whatever the base/GUI bin template renders; component styles limited to the device's reported `FcDialComponent` list and style counts. The SDK does not define a free widget language.
- No compression documented beyond the binary layout internal to DialWriter; no public spec — format is SDK-generated, device-validated.

## 4. P6_ device info available via SDK (Phase 4)

`FcConfigFeature.getDeviceInfo()`: project/patch/flash/app strings, `IcType` (hardware platform), `isSupportFeature/isSupportPage/isSupportNotification` bitmasks (full Feature enum inventoried — 150+ flags). `getExtraFirmwareInfo()`: GNSS GPS + 4G modem versions. Screen shape/resolution: `requestLcdShape`/`requestDialPushInfo` → `FcShape`. No display-type (LCD vs AMOLED) or color-depth field exists. All exposed via the new "Query Device Info (FitCloud)" diagnostic — nothing hard-coded.

## 5. What was implemented (Phases 4–5)

Read-only, official-API-only:
- `bluebit.fitcloud.FitCloudDialInspector` — two queries (`queryDeviceInfo`, `queryDialInfo`) publishing human-readable reports to StateFlows; failures captured, never thrown.
- `AndroidFitCloudProvider` — two new diagnostics: "Query Device Info (FitCloud)" and "Query Watch Face Info (FitCloud)".
- `ProviderDiagnostic.result: StateFlow<String?>?` (core, default null) + `DeviceDetailScreen` renders the latest result under each diagnostic button.

Not implemented, per findings: dial switching (no API), custom dial push (Phase 6 — documented above, blocked pending a device-appropriate base bin), any DFU/UI-pack flashing (safety rules).

## 6. Build / test results

- `:app:compileDebugKotlinAndroid` — BUILD SUCCESSFUL
- `:app:compileKotlinDesktop` + `:app:desktopTest` — BUILD SUCCESSFUL (existing suite passes)
- `:app:assembleDebug` — BUILD SUCCESSFUL → `app/build/outputs/apk/debug/app-debug.apk` (2026-09-17)

No new unit tests: all new code is bound to the Android SDK singleton (not desktop-runnable); the testable surface is a pass-through of SDK query results. Feature-flag internals note: `DIAL_MULTIPLE`, `LCD_SHAPE`, `SPORT_UI_EDITOR`, `TP_UPGRADE` constants are `internal` in Kotlin metadata (public in bytecode) and cannot be referenced from Kotlin — the inspector reads LCD shape and dial multiplicity directly from query results instead.

## 7. Classification

**VERIFIED (public SDK API + official docs):** dial push/install/delete, dial info query, GUI dial component style switching, custom dial generation via DialWriter, sport/game UI pack DFU, lock-screen image, album push, page visibility config, device/firmware info query.

**INFERRED (present in SDK, not documented as an app feature):** `DfuType.UI` whole-UI resource pack flashing as a de-facto system-wide theme mechanism; push activates the pushed dial (wiki wording "replace a certain dial", no explicit activate verb).

**NOT FOUND:** system-wide theme API, system UI color API, menu background API, icon API, notification visual customization, free-form workout-screen styling, AMOLED/LCD display-type reporting, dial-select API for already-installed faces, public watch-face binary format spec.
