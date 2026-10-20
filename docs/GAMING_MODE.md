# Gaming Mode & the Gaming Engine

This document explains what **Gaming Mode** in CatSmoker actually does and
how `GamingEngine` (`features/gamingtools/engine/GamingEngine.kt`) makes it
happen.

## What Gaming Mode applies

Activation is a sequence of discrete, independently-failing steps. Each one
is **verified by reading the value back** — the report you see in the UI is
a list of measured facts, not a slogan. If a step is refused, it lands in
`GamingModeReport.unavailable` with the device's own reason rather than a
generic "failed".

In order:

1. **Capture a snapshot** of the user's real system state *before* anything
   is changed, so turning the mode off restores their configuration — not
   ours.
2. **Trim system caches**: `pm trim-caches`, `am compact background`,
   framework pinning.
3. **Suspend background apps** (`pm suspend --user 0 <pkg>`), skipping the
   game in play, the launcher, system-critical packages, and a hard allowlist
   (Shizuku, Magisk, CatSmoker itself). The user's own **Apps kept running**
   list is subtracted from every sweep half, so a kept app is never frozen,
   never gets the AppOps restriction below, and is spared by RAM Reclaim's
   force-stop pass. The **extra suspended apps** list adds packages; if a
   package ever appears on both lists, `SuspendSweep.resolveTargets` lets the
   keep list win. Every package this sweep attempts additionally gets
   `cmd appops set <pkg> RUN_IN_BACKGROUND ignore`, its prior mode recorded
   first and restored by deactivation.
4. **Focus Mode / DND** — silences notifications *only if* notification
   policy access is granted, and confirms the margin was actually reached.
5. **Hardware locks**: pin `min_refresh_rate`/`peak_refresh_rate` to the
   panel's max, lock the vendor's own display-mode switch where the OEM hides
   the panel behind one (vivo/iQOO — see "vivo/iQOO display-mode lock"), enable
   the OEM touch-response key, request fixed-performance mode, enable the two
   developer options (finish activities, limit cached processes), raise the
   kernel-node levers the device declares (see "Kernel node levers"), and
   restrict metered background data.
6. **Per-game extras** when a game is targeted: whitelist it from device
   idle, put it in the platform's performance game mode (`cmd game mode
   performance`, confirmed by `cmd game list-modes`), raise its own frame cap
   via `device_config`, and — on Android 12+ — apply a `game_overlay`
   intervention.

## Verified-by-read-back design

`settings put` exits `0` whether or not the key took. The engine therefore
never trusts exit codes for settings; it reads the value back after writing.
Rows in `GamingModeReport` such as `lockedRefreshHz`, `discardActivities`,
and `processLimit` are the *read-back confirmation*, or null/"not applicable"
rather than "failed".

> A null report field means "this didn't apply to my device" (wrong Android
> version, feature absent, already enabled by the user). It is deliberately
> distinguished from "refused".

## Fixed Performance Mode

`toggleFixedPerformanceMode` calls `cmd power set-fixed-performance-mode-enabled`.
This reaches `PowerManagerService` → the vendor power HAL's
`MODE_FIXED_PERFORMANCE`:

- It exists on **Android 11+**; older builds get a clear "phone too old" answer.
- Vendors may accept the call and ignore it, so success means *the framework
  accepted the request* — which is all any caller (including CTS) can tell.
- The point is **consistency**, not peak speed; the fixed point is often held
  slightly below the boost ceiling for thermal headroom.

## The GameManager CLI: mode and intervention are different commands

`cmd game set` takes an *intervention config* (`--fps`, `--downscale`, …); it
never takes `--mode`. The engine used to send `set --mode performance --fps
<peak>` anyway, which dies in `NumberFormatException` on Android 13 (POCO X3
NFC, 2026-10-19, found while cross-checking `reference/gamingtools/TMPAD`).
`GameCommandParser` now builds both shapes and parses both answers:

- `cmd game mode performance <pkg>` — selects the mode; `cmd game list-modes
  <pkg>` must report `current mode: performance`. A ROM that does not list
  performance logs "not offered by this ROM" instead of pretending.
- `cmd game set --fps <peak> [--downscale <factor>] <pkg>` — the intervention
  fallback, used only when the `game_overlay` table refuses the write;
  `cmd game list-configs <pkg>` must show the `Fps`/`Scaling` that was asked
  for. The FPS value is always the panel's measured peak, never a lower
  ceiling, and deactivation's existing `cmd game reset` clears the custom
  entry.

## vivo/iQOO display-mode lock

`RefreshRateController.lockVendorDisplayMode()` (rule: `VivoDisplayMode`) handles
the panels that ignore `min_refresh_rate`/`peak_refresh_rate`. Gated on
`isVivoOrIqoo()`; on any other device it answers "not applicable".

- The mode id is **never hardcoded**: the controller enumerates
  `Display.getSupportedModes()`, keeps the modes at the rate `peakHz()` already
  measured as the peak, and picks the largest panel among them (ties broken by
  the lowest id, so the choice is stable). A mode id is a display-driver fact —
  the reference's `4` means one 1080x2400@120 panel's mode, and something else
  (or nothing) elsewhere.
- Nothing is written when the panel is already in that mode; a panel with no
  mode at the measured peak writes nothing and reports the refusal instead of
  inventing an id.
- Two keys are written, both read back: `secure user_preferred_display_mode_id`
  (the enumerated id) and `global vivo_screen_refresh_rate_mode` (the vendor's
  own switch, `1`). Activation records both before writing, so deactivation puts
  the device back exactly as it was — "the key was not set" restores as
  `settings delete`, not as the reference's `-1`.

## Kernel node levers

`SnapdragonKernelNodes` holds the sysfs half of the HunterX-Reborn-II reference
payload that `SnapdragonCpuWiden` did not cover, as pure rules; `GamingEngine`
supplies presence gates, snapshot priors, read-back and restore.

Three rules apply to every family:

- **Raise-only, enable-only, never narrow.** No rule lowers a value, turns a
  boost off or removes a CPU from a set.
- **Every target comes from the device's own accounting** — a cpufreq policy's
  `scaling_available_frequencies` (its hardware max), its `related_cpus` (a
  cluster's real core count), or a ceiling the node itself declares. The
  reference's numbers (2803200 / 1766400 kHz, `input_boost_freq 0:1400000`) are
  written for a different SoC and are inexpressible by construction.
- **Ambiguity means no write.** A `hispeed_freq` raise requires a declared
  1..100 `hispeed_load` (0 reads as "disabled" or "always" depending on the
  kernel); `msm_performance/cpu_max_freq = 0` means "no cap declared", so only a
  declared ceiling is raised.

| Family | Node(s) | Rule |
| --- | --- | --- |
| schedutil | `up_rate_limit_us` | `0` when the device ships a non-zero limiter (the performance direction for a limiter on the way *up* is down) |
| schedutil | `hispeed_freq` | the policy's own hardware max when it is above the current value, gated on a declared `hispeed_load` |
| schedutil | `iowait_boost_enable` | `1` when the node declares `0` |
| msm_performance | `touchboost` | `1` when the node declares `0` |
| msm_performance | `cpu_max_freq` | each **declared** per-CPU ceiling raised to its policy max |
| core_ctl | `min_cpus` / `max_cpus` | raised toward the cluster's `related_cpus` count |
| cpu_boost | `input_boost_enabled` | `1` when the node declares `0` |
| cpu_boost | `input_boost_freq` | each CPU's boost frequency raised to its policy max, only when the device declares an `input_boost_ms` window |

Deliberately never written: `down_rate_limit_us` (a raise is an invented
constant), `pl` (a mechanism toggle, not a magnitude), `hispeed_load`,
`input_boost_ms`, `msm_performance/cpu_min_freq` (a floor with no
device-derived target), and `core_ctl`'s
`enable`/`busy_up_thres`/`busy_down_thres`/`offline_delay_ms`/`task_thres`
(hotplug heuristics with no raise direction). The GPU half of the reference
payload is out entirely: its two `governor` writes reach one node through two
symlinks (the later one is the vendor's own default) and KGSL's
`min_pwrlevel`/`max_pwrlevel` are a live thermal clamp. `SnapdragonKernelNodesTest`
pins all of this — the writable-name list fails the build if any name starts
naming a GPU, pwrlevel or thermal control, and the read-only gates are asserted
to stay out of it.

A device without the `msm_performance`, `core_ctl` or `cpu_boost` modules simply
skips those families (the lab POCO X3 has `schedutil` and KGSL only), and the
Gaming Mode report names what was written with its read-back value.

## Read-only evidence probes

Gaming Mode logs, without writing anything, the OEM refresh keys the device
carries (`OemRefreshKeys` via `RefreshRateController.probeOemKeys()`) and —
on Kirin/Exynos/Unisoc/MediaTek silicon — the vendor performance properties it
carries (`VendorPerformanceKeys` plus `DeviceCapabilities.isKirin/isExynos/
isUnisoc`). Both vocabularies come from counter-example references
(JordanTweaks 120FPS, BoostX); they exist so a future lever can argue from the
device's own answer instead of a copied script, and `ReferenceRejectionsTest`
keeps those scripts' mechanisms out of the source. No write path consumes
either probe today.

## ART Optimizer (UI: "App Compile Optimisation", scope: one game or all)

`runArtOptimization` recompiles installed apps with real `cmd package
compile` dexopt:

- Skips apps already in the requested filter (querying `dumpsys package
  dexopt` first), unless `force` is set.
- Excludes CatSmoker's own package so a recompile can't kill the process
  doing the compiling.
- Counts results from what the shell actually answered (compiled / skipped /
  failed), feeds a live progress bar, and supports cancellation that never
  overwrites a completed or cancelled outcome.
- Uses `ProcessBuilder("su", ...)` directly for root so the in-flight
  process can be `destroy()`ed on cancel (Shizuku binder calls block).

## RAM Reclaim (UI toast prefix "RAM Reclaim:")

`manualBoostRam` measures `MemAvailable` (from `/proc/meminfo`, falling back
to `ActivityManager`) **before** trimming, then trims caches, force-stops
background targets, waits for the kernel to reclaim, and measures again. The
freed amount is the difference — reported as `null` rather than `0` when the
device won't reveal memory figures.

## Snapshot & revert

Activation persists a snapshot of every setting it touches plus the exact set
of packages it suspended. Deactivation can therefore:

- Un-suspend precisely the packages that were frozen.
- Restore each package's prior `RUN_IN_BACKGROUND` mode (a package with no
  override is stored as `default`), verified by reading the mode back; a
  refused restore stays recorded and is retried by the next deactivation.
- Restore the user's original refresh/touch/DND values.
- Only touch background-data restriction if **we** engaged it (never turn off
  a restriction the user set themselves).

## Engineering contract (adding a lever)

`GamingOptimizationSnapshot` is captured *before* anything is written and
persisted as JSON (`last_snapshot` in `gaming_engine_prefs`) so a restore
survives process death. Every field is either the value the device actually
held, or `null` meaning "could not read it — do not touch it on the way back".
Nothing is an assumed default; restoring one silently overwrites a preference
the user set on purpose. Copy these cases when adding a field:

- `activityManagerConstants` stores the **whole** `global
  activity_manager_constants` CSV, so the ROM's other constants stay alone.
- `backgroundDataRestrictedBefore` / `uidWhitelistedBefore` record a
  restriction that was already engaged, so deactivation leaves the user's own
  switch alone.
- `originalInterruptionFilter` exists because deactivation once hard-coded
  `INTERRUPTION_FILTER_ALL`, cancelling the user's own DND.
- `gameOverlay` reuses `SettingValue` so three states stay distinct: an
  intervention existed (put back verbatim), none (delete ours), unreadable
  (`null` → touch nothing).
- The kernel-node priors (`snapdragonCpuNodes`) are captured **only for nodes
  the raise-only rules would actually write** — a node already at its target, a
  family the device does not carry, or a rule gated away by an ambiguous
  neighbour (`hispeed_load`, `input_boost_ms`) contributes no snapshot entry, so
  the restore walks exactly the nodes that were changed and nothing else.

Network blocking is two switches over one app set (user-installed apps holding
`INTERNET`, minus CatSmoker and the game library), never a per-app matrix:
`VpnFirewall` claims a `tun` and never reads it — blocked apps *time out*, and
Android allows one active VPN, so it and any other VPN evict each other — and
`BackgroundDataRestrictor` uses Android's own `cmd netpolicy` plus Data Saver.
Both post-mortems are in the KDoc (Data Saver alone blocked nothing in
background; the first `VpnService` blackholed the *game's* traffic while
reporting success without calling `VpnService.prepare()`); read it before
touching either switch.

Auto Force Stop **keeps; it does not kill**: `KeepAliveStore` holds the apps
it must leave alone, and the prefs key was renamed with the meaning
(`keep_alive_packages`) so old installs start from an empty keep list. Don't
"fix" that migration gap — reinterpreting the old set as protected was the bug.

## Key state types

- `GamingModeState` — `Idle` / `Enabling(progress)` / `Active` / `Disabling` / `Error`.
- `GamingModeReport` — verified outcomes + a human-readable `unavailable` list.
- `BoosterState` / `BoosterOutcome` — live progress and a terminal, never-rewritten outcome.

## Secrets to keep in mind
- The device names referenced in the code (Vivo/iQOO packages, launchers,
  etc.) are behavioral heuristics, not declarations — changes there affect
  what a particular device class gets.
