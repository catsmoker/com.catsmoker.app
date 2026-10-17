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

## Engineering rules (`src/full`)

### Target-process constraints (Xposed)

`assets/xposed_init` names `features.spoofdevice.root.LSPosedModule`; the
manifest carries `xposedmodule` metadata. One APK, two execution contexts: the
app (Compose/Hilt/shell) and — inside every scoped target process — only
`LSPosedModule`, `GetPropInterceptor` and the `LSPosedConfig` model. Inside a
target there is no Hilt, no `Context`, no `filesDir`.

- `LSPosedModule` and `LSPosedConfig` are `-keep`ed in `proguard-rules.pro`:
  renaming or moving either silently breaks release builds.
- The Xposed API is `fullCompileOnly`; never reference
  `de.robv.android.xposed.*` outside `features/spoofdevice/root/`.
- Every hook install is wrapped in `try/catch` — target classes (GMS,
  `MediaDrm`, vendor `SystemProperties`) are often absent, and a miss is
  normal, not an error.
- **Both channels or neither.** Device-info readers use both
  `SystemProperties` and a shelled `getprop`; hooking one and not the other
  creates a disagreement that is a stronger signal than real values.
  `GetPropInterceptor` overlays only `ro.`/`persist.`/`gsm.`/`net.`/`dalvik.`/
  `sys.`/`vendor.`/`debug.` keys onto a real dump; a profile's own app keys
  (e.g. `device.imei`) must never appear in a getprop dump.
- `@array/scope` (`res/values*/spoof_scope.xml`, the only array in
  `strings.xml`) gates which packages the framework loads the module into.
  Our own package is deliberately **not** listed: recommended-scope entries
  are mandatory, so listing ourselves would lock CatSmoker as selected and
  force module loads (and detection) through our own process; Vector loads the
  module into its own app anyway. `SpoofScopeTest` pins the set (no dupes,
  no self). `NEVER_SPOOF_PACKAGES` is checked first.

### Config pipeline

One vocabulary shared by UI and hooks: Android system-property names
(`Build.MODEL` derives from `ro.product.model`; never duplicate it). One
rendered profile serves every channel:

```
SpoofRepository (filesDir/app_profiles.json, Gson)
  └─ renderConfig(profile, globalProps) → "key=value\n"   (ProfileRenderer; disabledFields omitted)
       ├─ SpoofConfigProvider          exported ContentProvider (freshest)
       ├─ Settings.Global (base64)     KEY_GLOBAL_PROFILES_B64, [pkg] sections
       ├─ Settings.Global (base64)     legacy single-profile keys
       └─ MagiskModuleBuilder          exported ZIP
```

Targets read in that order; API 30+ package-visibility filtering can hide the
provider from a target app, so the settings channel is the workhorse.
`SpoofConfigProvider` is necessarily exported but defends itself in
`resolvePackageName`: a caller receives only its own (or a shared-UID
sibling's) profile, and safe mode is honored on whichever channel answers.
Reading is unprivileged; publishing (`settings put global` via `ShellRunner`)
needs root, Shizuku or `WRITE_SECURE_SETTINGS`.

`SpoofRepository.update` writes disk **before** publishing to its `StateFlow`,
then broadcasts `LSPosedConfig.ACTION_CONFIG_CHANGED` so running targets
reload without a force-stop. `StoreData` collections are read-only — in-place
mutation once made a new snapshot compare equal to the old one and swallowed
the UI update. Presets are templates: edits `copy()`; never mutate a preset.

Per-field publish switches (store v4, 2026-10-17): `DeviceProfile.disabledFields`
lists editor field ids (vocabulary: `ProfileToggleFields`) whose keys the renderer
drops — a single filtering point, so every channel and preview agrees. An omitted
key is the same "leave the real value alone" signal a blank field already emits,
so the Xposed module needs no changes. Values are never rewritten (switching a
field back on republishes the same values). The generated Magisk module is
outside the filter: it flashes only its bounded model-key set and falls back to
the profile's own `model`.

### Magisk module shape

`MagiskModuleBuilder` generates all six members in code (there is no
`assets/magisk/` skeleton), builds scripts with explicit `\n` so no CR reaches
`#!/sbin/sh`, and takes version values from `BuildConfig`. `MODULE_ID` is
fixed on purpose: Magisk keys installed modules by `id`, so changing it
installs a second module.

`system.prop` carries `MODEL_KEYS` + `persist.sys.pixelprops.game` and nothing
else. It applies device-wide via `resetprop` at post-fs-data, before the
framework starts — a hardware key that disagrees with the silicon can prevent
boot, so fewer properties is strictly better; never add one unless something
is known to read it. Omitted keys are named on three surfaces (`system.prop`
comments, `customize.sh` output, the export toast). `customize.sh` also lists
any other enabled module whose `system.prop` shares keys (resetprop is
last-one-wins) and states the module's device-global nature (per-app Safe Mode
cannot apply). `service.sh` waits for `sys.boot_completed` (bounded; a timeout
is recorded, not assumed away), re-reads every key with `getprop`, keeps
**not set** distinct from **overwritten**, and reports via `verify.log` plus a
live `description` reprinted from the same identity lines.

### Status: enabled ≠ loaded

`refreshStatus` combines a heartbeat with `VectorActivation` evidence, and the
status card keeps three facts apart: enabled in the framework manager, loaded
into the target process, and actually serving values. `modules ls` can say
"enabled" with no own-process load (fresh install), and reinstalling wipes
module prefs while the daemon log still proves a load — so loaded-proof is
heartbeat OR daemon-log evidence. On a Vector-hooked device the module's
prefs resolve to `/data/misc/<uuid>/prefs/<pkg>/` (the `xposed_data` broker),
not `/data/user/0/<pkg>/shared_prefs/`; a plain `run-as` read shows the stale
path.

### What actually raises a frame-rate cap

No property unlocks FPS. Two mechanisms work: **be a model the game's device
data lists at the tier you want** — FPS tiers are tables keyed on
`ro.product.model`; being fast is not the criterion, being in the table is —
or **edit the game's own config** (see `FILE_ENGINEERING.md`). Android 12+
Game Interventions (`device_config put game_overlay <pkg>`) are a third,
system-side lever. `SpoofRepository.getPresets()` is author-curated — see
`DECISIONS.md` for the curation rule and what was rejected.

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
