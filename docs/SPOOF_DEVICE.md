# Spoof Device (Full flavor only)

How CatSmoker lets a game see a different device — and the three channels it
ships for applying a spoof. Everything in this document lives in `src/full`
and is **absent from the Play Store flavor** (same-FQN shims + capability
flags keep shared code compiling against both — see `FLAVOR_WORKFLOW.md`).

## What is spoofed

A "device profile" is a set of Android system properties (`ro.product.*`,
`ro.build.*`, vendor/fingerprint/security-patch values, etc.) imitating a
premium device so a game unlocks higher graphics settings, frame caps, and
quality presets.

Configuration lives in `SpoofConfigProvider`
(`src/full/…/system/config/SpoofConfigProvider.kt`) serving `DeviceProfile`
presets (`src/full/…/shared/data/model/DevicePreset.kt` + `DeviceProfile.kt`).
Profiles are persisted as JSON (Gson).

## Three application channels

| Channel              | When it's used               | How it works                                                                                           |
| -------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------ |
| **Xposed framework** | Rooted devices (recommended) | Hooks the target game at runtime and overrides `getprop` for the spoofed keys. Zero file modification. Works with LSPosed or Vector (both expose the legacy Xposed API the module is written against). |
| **Shizuku**          | Non-rooted, Android 11+      | Uses the Shizuku binder to run privileged commands that write/oversee system or game data.             |
| **Magisk module**    | Offline / system-level       | `MagiskModuleBuilder` generates a flashable `.zip` that applies the spoof at boot via `system.prop`.   |

Plus **SAF / export mode** for manually granting access to game data folders.

## Xposed module (LSPosed / Vector)

- `src/full/…/features/spoofdevice/root/LSPosedModule.kt` — declares the module scope
  and lifecycle.
- `src/full/…/features/spoofdevice/root/GetPropInterceptor.kt` — the actual runtime
  hook that answers `getprop`/`ro.product.*` for the selected game with the
  active profile's values.
- Activation is per-game: the framework manager (LSPosed manager or Vector
  manager) scopes the module to the games
  you want optimized. (Verified 2026-10-09 on POCO X3 NFC: Vector v2.2 loads
  the Xposed environment into the scoped game process; the module uses only
  the legacy `de.robv.android.xposed` API, which Vector supports, so no
  framework-specific code is needed.)

**Important:** the Xposed API is a `fullCompileOnly` dependency (never
packaged). The module classes are only meaningful inside the Xposed framework
process (LSPosed or Vector);
the app itself keeps running on a normal Android classpath.

## Magisk mode builder

`MagiskModuleBuilder` (`src/full/…/features/spoofdevice/tools/MagiskModuleBuilder.kt`)
constructs a flashable Magisk module **entirely in code** as a `ZipOutputStream`.
Two past bugs explain why:

1. **Silent coupling** — the exported zip used to inherit whatever members the
   asset tree happened to contain, so `system.prop` (the file that carries the
   spoof) existed only because a placeholder sat in `assets/`. Deleting the
   placeholder would have shipped a module that installs cleanly and spoofs
   nothing.
2. **Stale `module.prop`** — `version`/`versionCode` were hardcoded and drifted
   while the app moved on. Both now come from real build values.

The builder deliberately flashes only a narrow allowlist of model keys
(`MODEL_KEYS`), not the whole profile, and reports any omitted keys rather
than silently dropping them — narrow is the safe shape for this channel.

The `customize.sh` prints the device's *real* model during flashing (so a
user sees what is being replaced), and `service.sh` waits for boot to settle
before it trusts anything it reads, then reports what the device actually did
after applying.

## Routing & screens

Routes in `system/navigation/Routes.kt`:

- `spoof_device` — main hub
- `spoof_profiles` / `spoof_editor/{profileId}` — list & edit profiles
- `spoof_apps` — choose which games a profile applies to
- `spoof_diagnostics` — verify spoof state
- `spoof_safe_mode` — revert to safe state (see below)

## Safe mode

Spoofing can make a game (or, rarely, the OS) behave unexpectedly. The
`SPOOF_SAFE_MODE` flow exists to remove active hooks/profiles and restore a
known-good state quickly. Keep this path robust — it is the emergency exit
for anyone who applied a spoof that broke something.

## A note on scope

Spoof identity is per-game and per-profile. Toggling, editing, or deleting a
profile never touches other games' scopes, and the diagnostics screen reads
back actual `getprop` output rather than showing the intended values.
