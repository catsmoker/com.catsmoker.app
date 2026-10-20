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
| **Xposed framework** | Rooted devices (recommended) | Hooks the target game at runtime and overrides `getprop` for the spoofed keys, plus — through the framework's native module API — the target's own native property and GL-string reads (see "Native channel" below). Zero file modification. Works with LSPosed or Vector (both expose the legacy Xposed API the module is written against). |
| **Shizuku**          | Non-rooted, Android 11+      | Uses the Shizuku binder to run privileged commands that write/oversee system or game data.             |
| **Magisk module**    | Offline / system-level       | `MagiskModuleBuilder` generates a flashable `.zip` that applies the spoof at boot via `system.prop`.   |

Plus **SAF / export mode** for manually granting access to game data folders.

## Xposed module (LSPosed / Vector)

- `src/full/…/features/spoofdevice/root/LSPosedModule.kt` — declares the module scope
  and lifecycle.
- `src/full/…/features/spoofdevice/root/GetPropInterceptor.kt` — the actual runtime
  hook that answers `getprop`/`ro.product.*` for the selected game with the
  active profile's values.
- `…/root/AndroidIdDerivation.kt`, `…/root/EmulatorArtifacts.kt`,
  `…/root/SpoofHardwareFacts.kt` — pure helper objects for the M146/M116/M117
  surfaces (per-app seed derive, emulator-file predicate, hardware-fact
  conversions); see "Extra spoof surfaces" below.
- Activation is per-game: the framework manager (LSPosed manager or Vector
  manager) scopes the module to the games
  you want optimized. (Verified 2026-10-09 on POCO X3 NFC: Vector v2.2 loads
  the Xposed environment into the scoped game process; the module uses only
  the legacy `de.robv.android.xposed` API, which Vector supports, so no
  framework-specific code is needed.)
- **Unity frame-rate delivery (M152, 2026-10-19):** when a profile is assigned,
  `UnityFrameRateDelivery` raises a Unity game's internal
  `Application.targetFrameRate` to the panel peak. Raise-only: the current value
  is read first and the setter runs only when `0 < current < peak` (Unity's
  `-1` = platform default is left alone; no opcode patching, no resolution
  writes). The bridge `.so` is loaded through the module class loader first
  (`NativeBridge.preloadFromClassLoader()` → the framework's `findLibrary`
  answers the packaged zip path) — that is also the load the framework's native
  API watches, so the same library carries the native spoof hooks (see "Native
  channel"). Extraction into the target's cache + `System.load` remains only as
  the fallback for a loader that cannot answer `findLibrary`; that route maps a
  second, unregistered copy. Calls run on the target's main thread (the Unity
  engine loop) after a non-blocking `/proc/self/maps` readiness probe, because
  calling an IL2CPP icall from a raw thread segfaults. A non-Unity process, a
  32-bit process, an incompatible IL2CPP build or a missing icall all abort
  cleanly. Unassigning the profile stops further retries. Per-process only:
  nothing persists across a game process restart, nothing outside the target's
  cache is written, and the effect is unverified on a title whose cap is
  actually below the panel peak.
  The resolver's address comes from the library's **executable** mapping, not
  the first offset-0 line: Android 15 also keeps a whole-file `r--p` duplicate of
  `libil2cpp.so` at offset 0, and treating it as the load base put the call in a
  non-executable page (`SEGV_ACCERR` at the resolver entry, POCO X3, 2026-10-20;
  fixed in `catsmoker_bridge.c`). Two limits observed on the same run are open
  defects of this channel, not of the native spoof hooks: a helper process that
  maps `libil2cpp.so` without a running runtime (MLBB's `:UnityKillsMe`) lets
  `il2cpp_resolve_icall` dereference null, and a Unity 6000.3 title raised a
  managed exception that null-derefs inside IL2CPP when the getter was called
  from the main thread (the spoof hooks had already installed and probed in both
  processes before the fault).

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
sibling's) profile, and the per-app lists are honored on whichever channel
answers.
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

Per-app skip lists (2026-10-17, M113): `globalProperties` also carries
`safe_mode.packages` (full skip) and `version_bypass.packages` (keep the real
Android version) into every rendered profile. The module resolves both once in
`LSPosedConfig.applyUserLists` at the top of `readConfig` — see Safe mode
below.

### Extra spoof surfaces (2026-10-18)

Three more target-process surfaces, each blank/off by default and each with
its own honesty limits (rationale in `DECISIONS.md`):

- **User-declared hardware facts (M117).** `hwCpuCores` / `hwRamMb` /
  `hwHeapMb` / `hwLargeHeapMb` render as `hw.cpu_cores`, `hw.ram_mb`,
  `hw.heap_mb`, `hw.large_heap_mb` — only while > 0. The module hooks
  `Runtime.availableProcessors`, `ActivityManager.getMemoryInfo` (totalMem, in
  both the out-param and returning shapes), `getMemoryClass` /
  `getLargeMemoryClass`, and `RandomAccessFile.readLine` for the
  `/proc/meminfo` `MemTotal:` line. The MB→bytes/kB conversions live in
  `SpoofHardwareFacts` (single authority). Not covered: native reads,
  `Runtime.maxMemory`, `Debug.*`, `availMem`, and `/proc/cpuinfo` text.
  Presets never fill these fields — the numbers are the user's own.
- **Emulator files (M116).** `hideEmulatorArtifacts` renders `emulator.hide=1`.
  `java.io.File.exists()` answers false and all three `listFiles` overloads
  drop matching children; `EmulatorArtifacts` holds the reference's exact path
  list plus `goldfish`/`ranchu`/`vbox`/`qemu` keywords, bounded to `/dev`,
  `/proc`, `/sys`, `/system`, `/vendor`, `/product`, `/odm`. Native opens and
  shelled listings bypass it — Java probes only.
- **Per-app Android ID seed (M146).** `androidIdSeed` renders `device.id_seed`.
  When a literal `ANDROID_ID` / `ro.serialno` is absent, `readConfig` injects
  `derive(seed, packageName)` / `derive(seed, "serial:" + packageName)` into
  the parsed map (`AndroidIdDerivation`: FNV-1a 64 → splitmix64 → 16 hex,
  byte-parity with the reference's `deriveAndroidId`). One injection point, so
  the Settings.Secure hook, `Build.getSerial`, the `SystemProperties` reads
  and the getprop dump merge cannot disagree. Literals always win; a blank
  seed changes nothing.

None of these keys is a system property, so the Magisk channel's
`filterToSystemProperties` drops them all (test-pinned); the module's `getprop`
overlay carries only the derived `ro.serialno`, which is `ro.`-prefixed on
purpose.

### Native channel (2026-10-20, M127/M147)

The Java hooks and the shelled `getprop` reach every Java reader, but a game
SDK, an anti-cheat library or a Unity/Unreal renderer reading a property inside
its own native code still sees the device — and a process answering two
different models to two of its own readers is worse off than one answering the
real device to both. `NativeSpoofChannel`
(`src/full/…/features/spoofdevice/root/NativeSpoofChannel.kt`) closes that gap;
the hooks live in `app/src/main/jni/catsmoker_spoof.c` (full-flavor-only
translation unit, like the class).

How it arrives: LSPosed/Vector read a module's `assets/native_init` (legacy
modules; modern ones use `META-INF/xposed/native_init.list`), register each
named library, and their `do_dlopen` hook calls a matching library's
`native_init` — handing it `{version, hookFunc, unhookFunc}` (Dobby inline
hooking, provided by the framework; nothing is bundled). The module's
registration file is `src/full/assets/native_init` (contains
`libcatsmoker_bridge.so`) and the export is in the spoof translation unit, so
the Play APK and any non-spoofed device carry neither half.

Design rules, each forced by that API:

- **No `dlopen` and no hook install inside `native_init`.** The framework holds
  its module-registry mutex across the call, so `native_init` only records the
  hook pointer and returns the library-loaded callback. Hooks install lazily:
  the property hooks from the JNI setter, on the first non-empty profile; the
  GL hooks from the callback when the process loads `libGLESv2.so` /
  `libGLESv1_CM.so`. A GL library that was already mapped before the module
  attached keeps the device's strings — a documented limit, visible in the
  state mask.
- **One native copy.** The library must be loaded through the module class
  loader (`NativeBridge.preloadFromClassLoader()`), because that is the load
  the framework watches (its `findLibrary` answers the packaged zip path, which
  requires the `.so` to be a STORED zip entry). An extracted copy loaded by
  path is a second copy the framework never sees; that route is only the
  fallback for a loader that cannot answer `findLibrary`.
- **Tables, not parsers.** The JNI setters receive fixed-grammar payloads
  (`key=value` lines; `prefix|suffix=canonical` alias rules) and store them in
  fixed-size tables, reporting overflow back to Java. Values are truncated to
  bionic's `PROP_VALUE_MAX - 1` (91 characters) — the truncation a reader of the
  real property would have seen. The property table holds 128 entries because a
  rendered preset is ~80 lines including aliases: on the first device run the
  64-entry table dropped 16 keys (`props=64/80`), which would have left the Java
  half answering the profile for keys the native half answered from the device —
  the exact disagreement this channel exists to prevent. `dropped > 0` in a field
  log therefore means the preset shape outgrew the table, never that the drop is
  harmless.
- **Alias parity is data-driven.** `PropAliases` (`DIRECT` + `SUFFIX`) is the
  single authored vocabulary: Java pre-expands the direct aliases into the table
  and serializes the suffix rules, so the Java lookup and the native lookup
  cannot drift apart.
- **State mask, read back from the library.** `nativeChannelState()` reports
  which hooks actually installed (`native_api`, `prop_get`,
  `prop_read_callback`, `prop_read`, `gl_gles2`, `gl_gles1`, `configured`) and
  `install()` logs `CatsmokerNative: state=… props=n/m` plus a probe comparing
  the profile's model with what a native read answers in that process — evidence
  from inside the target, never a self-report. Each GL hook also logs its own
  success (`glGetString hooked in libGLESv2.so`), so the five-second gap between
  the property state line and engine init is readable.
- **Device-verified (2026-10-20, POCO X3 NFC, Vector 2.2, Adreno 618):** in a
  scoped Unity GLES title the target process logged
  `native_init: api version 2`, then
  `state=native_api+prop_get+prop_read_callback+prop_read+configured props=80/80`
  and `probe ro.product.model src=GM45K seen=GM45K` (the device's own model is
  `M2007J20CG`), followed by `glGetString hooked in libGLESv2.so` once the engine
  loaded the wrapper. In the Vulkan-only Unity target no GL hook installed: the
  wrapper was mapped before the module registered (the documented already-mapped
  limit), so the property half — which does not depend on engine init — carried
  that process.

- **No writes, no fabricated answers, no unhook.** Reads are answered; every
  other call passes to the real function. A key the profile does not carry
  answers the device's own value, an absent property stays absent, and
  `unhookFunc` is deliberately never called (the hooks become pass-through
  tables; Dobby-destroying a target while other threads execute is a crash).
  EGL strings (`eglQueryString`) are platform strings and stay untouched.

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

Spoofing can make an app (or, rarely, the OS) behave unexpectedly, and a
spoofed Android version is the usual crash cause. The Safe Mode screen holds
two independent per-app lists (both in `globalProperties`, so they ride inside
every rendered profile and all channels agree):

- **Full skip** (`safe_mode.packages`) — `readConfig` returns an empty map for
  the package: no hooks install, no field overrides, the app sees the real
  device. Our own port decision; in the reference this list means version-only.
- **Version only** (`version_bypass.packages`, M113 2026-10-17) — the profile
  is delivered minus `LSPosedConfig.VERSION_PROPERTY_KEYS`
  (`ro.build.version.` + release / sdk / security_patch / incremental, plus the
  `release_or_codename` and `release_or_preview_display` aliases) at that one
  point, so every hook, Java property read and `getprop` answers the real
  values; model and fingerprint stay spoofed. The fingerprint deliberately
  keeps the profile's version string, which the screen note names as the one
  intended disagreement.

Precedence: full skip wins when both lists name a package. The generated
Magisk module is device-global and applies neither list (already stated on its
surfaces). Keep this path robust — it is the emergency exit for anyone who
applied a spoof that broke something.

## A note on scope

Spoof identity is per-game and per-profile. Toggling, editing, or deleting a
profile never touches other games' scopes, and the diagnostics screen reads
back actual `getprop` output rather than showing the intended values.
