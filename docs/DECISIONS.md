# Design Decisions & Rejected Patterns

Why the codebase is shaped the way it is, and which approaches have already
been evaluated and turned down. Entries are deliberately short — the operative
rules live in the other documents in this folder ([SECURITY.md](SECURITY.md),
[SPOOF_DEVICE.md](SPOOF_DEVICE.md), [GAMING_MODE.md](GAMING_MODE.md),
[FILE_ENGINEERING.md](FILE_ENGINEERING.md), [CODING_STYLE.md](CODING_STYLE.md));
this file records the reasoning and the history so a rejected idea is not
re-proposed without new evidence.

When a decision is reversed, add an entry that argues with the old one — do
not silently rewrite history.

## Product direction

- **Maximum performance, never cap FPS** (2026-09-23, non-negotiable). No
  caps, limiters, "stable FPS" ceilings, or thermal/battery auto-capping.
  Removed on sight: the per-game frame-cap picker, a generated module's
  `fps-targets.conf` + WebUI, PUBG 60/90 tier profiles. `FpsTargetsTest.kt`
  stays as an empty placeholder so the removal stays visible.
- **FPS layers are separate problems** (2026-09-25): game FPS vs engine limit
  vs display refresh vs compositor vs frame pacing vs GPU/CPU/thermal vs
  game/vendor limits. Investigate the actual bottleneck, remove artificial
  limits, and label risk honestly (possible / prohibited / detectable /
  account-risk). Never design around anti-cheat detection.
- **Shizuku only — no wireless ADB pairing** (2026-09-20): `system/adb/` was
  removed; the privilege ladder is Android APIs → Shizuku → root → generated
  Magisk module. Do not re-add ADB pairing without a new entry.
- **Not a recording app** (2026-10-17): no microphone/voice/video/screen
  recording or streaming. The Logs screen's Copy/Share is a diagnostic; the
  session-CSV recorder was removed with the live-viewer redesign
  (`SessionRecorder`, `AppExitHistory`) and `LogsScreenSimplicityTest` bans
  its return.
- **Workarounds are first-class** (2026-09-25): when the APK cannot act
  directly, generate something the user can apply (Magisk module, config,
  patch, script). Never stop at "Android doesn't allow this".
- **Universal core + capability detection + device/vendor extensions**
  (2026-09-25): vendor-only capabilities are extensions, never rejection
  reasons. Detect the device; expose when supported, hide when not; similar
  mechanisms may coexist with auto or user selection.
- **The reference library is a technical arsenal**, not an archive
  (2026-09-25): audit entries for reusable capability, compare against the
  app, propose reuse. Per-entry provenance verdicts live in the reference
  library's own `AGENT.md` (developer machine, not part of this repository).

## Spoofing & device identity

- **Presets are author-curated** (2026-09-20). `SpoofRepository.getPresets()`
  changes only when the author names a model. Substituting reference-module
  model strings once replaced verified data with stale hearsay; it was
  reverted. Never add, reorder, or remove a preset on judgment alone.
- **Spoof store v2 defaults to Pixel 11 Pro** (2026-09-25). The v1 seed
  (Pixel 8 Pro) no longer matched any preset; pristine defaults migrate via
  `repaired()` and customized profiles are never touched. The custom template
  lives outside `getPresets()` to keep the curated list intact.
- **The Magisk channel must narrow** (2026-09-20). `system.prop` carries
  `MODEL_KEYS` + `persist.sys.pixelprops.game` and nothing else — it applies
  device-wide before the framework starts, and a hardware key that disagrees
  with the silicon can prevent boot. Omissions are named on three surfaces,
  never silent. Fewer properties is strictly better here.
- **Publish switches filter at the renderer, never the model** (2026-10-17).
  `disabledFields` omits owned keys inside `ProfileRenderer`; the profile keeps
  every value and the module's blank-means-off contract carries the signal, so
  switching a field back on restores identical output. Rejected: stripping
  values at save time (destroys data; contradicts "presets are templates") and
  filtering per channel (channels could disagree). The Magisk channel stays
  outside the filter — its bounded key set is bootstrap-critical.
- **Version-only bypass strips at the module gate** (2026-10-17, M113). A
  second per-app list (`version_bypass.packages`) keeps the real
  `ro.build.version.*` values (release, sdk, security_patch, incremental plus
  the `release_or_codename` / `release_or_preview_display` aliases) while model
  and fingerprint stay spoofed — the reference's `shouldBypassVersionSpoof`.
  The strip happens once, in `LSPosedConfig.applyUserLists` at `readConfig`, so
  every hook and the getprop interceptor fall through to real values with no
  per-hook code; safe mode (full skip) wins when both lists name a package.
  The fingerprint keeps the profile's version string on purpose — the screen
  note names it as the one intended disagreement. The Magisk module applies
  neither list (device-global). The screen copy had described version-only
  while the shipped code did a full skip; the section text now describes both
  lists accurately.
- **Per-app IDs derive from a seed, never fabricate** (2026-10-18, M146). One
  profile seed (`device.id_seed`) yields a distinct, stable Android ID and
  serial per assigned app when the literal fields are blank; literals always
  win, and the injection happens once at `readConfig` so every reader agrees.
  The reference's `clean()` (strip from the first `:`) is deliberately not
  ported — it exists for its tagged-package syntax and would eat our
  documented `serial:` domain prefix. Algorithm parity is pinned against
  vectors computed from the reference JavaScript itself (charwise `&0xff`
  mixing, splitmix64 finalizer). Serial-from-one-seed is our documented
  extension, not a reference behavior.
- **Emulator concealment is bounded and opt-in** (2026-10-18, M116). The
  reference's bare substring test would hide `/sdcard/notes/vbox.txt` too;
  keyword matching stays inside the system subtrees, exact paths unchanged.
  Its `ro.kernel.qemu=0`-style property zeros are not ported — a real device
  lacks those keys, and *adding* them is itself emulator-shaped. Off by
  default, Java `File` probes only (native and shelled reads stay real); a
  gate is its own suppressor, so it has no publish switch.
- **Hardware facts are user-declared, never preset-authored** (2026-10-18,
  M117). A fabricated RAM/cores number is the fabricated-codename mistake in
  another register: presets leave all four fields at 0, and 0 means the hook
  is never consulted. Adapts the reference's `HardwareHooks` minus its
  Debug/availMem rescaling and any cpuinfo content — untouched surfaces stay
  real and the class KDoc names them.
- **`StoreData` collections are read-only** (2026-09-23). In-place mutation
  made a new snapshot compare equal to the old one and swallowed the UI update.
- **A partial spoof is worse than none.** Device-info readers use both
  `SystemProperties` and a shelled `getprop`; hooking one channel creates a
  disagreement that is a stronger signal than real values. Hook both or
  neither.
- **A module has a native surface — M127/M147 re-decided** (2026-10-20). Both
  rows were closed 2026-10-19 as "no LSPosed-OUT surface; G4", on the
  assumption that the framework gives a module only the Java hook API.
  `reference/lsposed-vector` disproves it: the framework reads a module's
  `assets/native_init` (legacy modules) or `META-INF/xposed/native_init.list`,
  registers the named libraries, and its `do_dlopen` hook calls a matching
  library's `native_init` with the framework's own inline-hook engine — the
  same both-channels question as the bullet above, one layer down
  (`NativeSpoofChannel` + `app/src/main/jni/catsmoker_spoof.c`, full flavor
  only). Constraints taken from that code, not invented: the registry mutex is
  held across `native_init` (so no `dlopen` and no hook install inside it — the
  property hooks install from the JNI setter on the first profile, the GL hooks
  from the library-loaded callback), `findLibrary` answers only STORED zip
  entries (so the module class loader is the one load route the framework sees,
  and the extracted copy stays a fallback), and `unhookFunc` is never called
  (the hooks become pass-through tables; destroying one while other threads
  execute is a crash). Still closed: Vulkan device strings — the rendered
  profile carries GL vendor/renderer only, so answering a Vulkan device name
  would need new profile keys and a value nobody authored; and nothing in G4
  changes — this answers reads, it is not an injection or memory-patching
  channel.
- **`assignments` vs `rateAssignments`** (2026-09-20): a non-empty ladder owns
  the package; the plain assignment is cleared when a ladder is built and vice
  versa.
- **NEVER_SPOOF includes root managers** (2026-09-27, device evidence).
  Spoofing `Build.VERSION` into Magisk's process crashed it twice (recovered
  by unassigning). Six packages were added — Magisk, KernelSU, APatch, the
  LSPosed manager, Shizuku, and CatSmoker itself — and `readConfig` logs the
  refusal. Forcing a refusal on device was deliberately skipped: it guards
  against the crash it would provoke.
- **Our own package stays out of the recommended scope** (2026-10-13). The
  framework treats recommended-scope entries as mandatory, so listing
  `com.catsmoker.app` locked it as selected and forced module loads through
  our own process. Vector loads the module into its own app anyway
  ("Always in scope"), so detection does not need the entry.
- **Enabled in the manager ≠ loaded** (2026-10-13). `modules ls` can report
  "enabled" with no own-process load (fresh install), and a reinstall wipes
  module prefs while the daemon log still proves the load — so loaded-proof is
  heartbeat OR daemon-log evidence (`VectorActivation`).
- **Rejected, do not re-propose**: cpuinfo bind-mount (the technique only —
  app-scoped hooks are preferred), blind `debug.hwui.*`/`debug.sf.*` prop
  batches (`debug.sf.latch_unsignaled` is frame pacing, not a cap; the
  HWUI/SurfaceFlinger reference stays deliberately uncross-checked), BoostX
  (its reference was deleted; `AudioBoostController` no longer tracks it), a
  whole-device "plausible" `system.prop` (~42 keys), thermal-disable,
  memory/process tampering, cheat planes, concealment/evasion, whole-blob
  config replaces (disposable/generated files only, with verified behavior),
  fake telemetry, remote catalogs, non-gaming utilities, and FPS caps of any
  kind.

## Gaming engine

- **Restore from a measured snapshot, never from defaults** (2026-09-20).
  Hard-coded restore values once overwrote the user's own DND setting and
  per-device state (GRID blobs, HSR fields). Every snapshot field is either
  the value the device actually held or `null` ("could not read it — do not
  touch it on the way back"); [GAMING_MODE.md](GAMING_MODE.md) lists the field
  cases to copy.
- **AppOps `RUN_IN_BACKGROUND` rides the suspend sweep** (2026-10-17). M42 was
  fully investigated 2026-10-09 and kept unimplemented until an op+target
  decision existed; the developer decision pins it here: activation sets
  `RUN_IN_BACKGROUND ignore` on every fresh suspend target, snapshotting the
  prior mode first (`No operations.` reads as the explicit `default` the
  restore writes back), and deactivation restores it with read-back and
  leftover retry — the same measured-snapshot rule as every other lever. It
  ships as a layer of the existing Gaming Mode opt-in, with no separate
  switch, and unreadable priors are skipped, never guessed. The push/sync
  pause while active is the intended effect and matches what `pm suspend`
  already did to the same set.
- **Network blocking is two switches, and neither is a firewall.** Data Saver
  alone blocked nothing in the background, and the first `VpnService`
  blackholed the *game's* traffic while reporting success. Both post-mortems
  live in `BackgroundDataRestrictor`'s KDoc; read it before touching either
  switch.
- **Auto Force Stop keeps, it does not kill** (2026-09-20). The keep-list key
  once meant the opposite of the feature's name; it was renamed with the
  meaning so old installs start empty. Reinterpreting the gap was the bug —
  do not "fix" it.
- **Notification suppression is cancel-only** (2026-09-20): self-reversing by
  construction, so deactivation needs no restore path.
- **Scheduled dexopt recurrence is app-owned** (2026-09-20): the `art`
  reference contributes worker shape only; it has no scheduling (and the
  schedule compiles `speed`, never `-f`).
- **The GameManager CLI's mode is a subcommand; `set` never takes `--mode`**
  (2026-10-19, TMPAD cross-check). The engine sent `set --mode performance
  --fps <peak>` for the game's mode; on an Android 13 POCO X3 that dies in
  `NumberFormatException: For input string: "performance"` — `set` parses its
  flags as an intervention config, and mode selection is its own `cmd game
  mode` subcommand. `GameCommandParser` now owns both shapes: mode via
  `cmd game mode performance <pkg>`, confirmed by `cmd game list-modes`, and
  the intervention config via `cmd game set --fps/--downscale`, confirmed by
  `cmd game list-configs`. The `device_config game_overlay` table remains the
  primary intervention path; `cmd game set` is the fallback only when the
  table namespace refuses the write, it carries only the measured peak (never
  a lower value), and the existing `cmd game reset` on deactivation clears it.
  This amends backlog M253: the `list-*` reads now have a consumer (read-back),
  while M253's no-mode-picker half stands — no user-facing mode selection was
  added.
- **OEM refresh keys and per-SoC properties are read-only probes**
  (2026-10-19, JordanTweaks/BoostX cross-check). Both references' write
  mechanisms are rejected: hardcoded `120` writes, `null`-string "restores",
  hard `debug.*fps*` caps, vsync-disable props, thermal-disable `persist.`
  keys, and synthetic CPU load. What ships is evidence gathering: the
  rate-bearing subset of JordanTweaks' key enum drives `OemRefreshKeys` (read
  through `RefreshRateController.probeOemKeys()`) and BoostX's vendor
  vocabulary drives `VendorPerformanceKeys` presence reads plus
  `DeviceCapabilities.isKirin/isExynos/isUnisoc`; Gaming Mode logs whichever
  keys the device actually carries. Neither probe writes, and any future write
  path for one of these keys needs the same three things every lever needs:
  device evidence that the key moves the panel, a measured prior, and a
  verified restore. `ReferenceRejectionsTest` bans the mechanisms source-wide
  so a later session cannot copy them back in.
- **The user keep list wins over the suspend sweep** (2026-10-19). Gaming Mode
  freezing every background app also froze the ones the user needed (chat,
  YouTube), so `SuspendExemptStore` (`exempt_packages` in
  `SuspendExemptPrefs`) is subtracted from all three sweep halves in
  `getSuspendTargets` and from the extra-suspend picks in
  `SuspendSweep.resolveTargets`. A kept app therefore also skips the AppOps
  `RUN_IN_BACKGROUND ignore` layer and RAM Reclaim's force-stop pass, and is
  never recorded in `affected_pkgs` because it was never frozen. The UI keeps
  the keep list and the extra-suspend list mutually exclusive, but the engine
  resolves a stale or hand-edited overlap as *kept* — the safe direction is
  the only direction. The store is deliberately separate from `KeepAliveStore`:
  that answers Auto Force Stop's different question (don't force-stop on
  leave), and merging them would couple two unrelated lifecycles. The list
  widens the user-level exemption only; `hardWhitelist`, `systemCritical`, and
  `gamingDaemons` stay code-owned, and no user list can remove an entry from
  them.
- **Kernel-node writes derive every target from the device, and can only raise**
  (2026-10-20, HunterX-Reborn-II cross-check). `SnapdragonKernelNodes` ports the
  node half of that module's `service.sh` as data plus pure rules (the mechanism
  and its numbers are separate decisions), and `GamingEngine` captures the prior
  from the live node, applies, and reads back — the measured-snapshot contract
  every lever already follows. Every target comes from device accounting: a
  cpufreq policy's `scaling_available_frequencies` (its hardware max),
  `related_cpus` (a cluster's real size), and a *declared* `cpu_max_freq`
  ceiling. The reference's constants are deliberately inexpressible — 2803200 /
  1766400 kHz were written for a different SoC, `input_boost_freq 0:1400000` is a
  hand-picked point, and its `up_rate_limit_us 1000` is *slower* than the 500 the
  lab device ships (for a limiter on the way up the performance direction is
  down, so the rule removes it: `0`). Deliberate non-writes, each with its reason
  in the class KDoc and pinned by `SnapdragonKernelNodesTest`:
  `down_rate_limit_us` (a raise there is an invented constant), `pl` (a mechanism
  toggle, not a magnitude), `hispeed_load` (an ambiguity — 0 reads as "disabled"
  on some kernels and "always jump" on others), `msm_performance/cpu_min_freq`
  (a floor with no device-derived target), `core_ctl`'s
  `enable`/`busy_up_thres`/`busy_down_thres`/`offline_delay_ms`/`task_thres`
  (hotplug heuristics with no raise direction), and `input_boost_ms` (a boost
  window; with none declared the boost never happens, so raising its frequencies
  would be a write that changes nothing). The whole GPU half of the reference's
  payload stays out: its two `governor` writes reach one node through two
  symlinks (the later one, `msm-adreno-tz`, is the vendor's own default) and
  KGSL's `min_pwrlevel`/`max_pwrlevel` are a live thermal clamp. Families a
  device does not carry are skipped, never reported as success — the lab POCO
  has `schedutil` + KGSL but no `msm_performance`, `core_ctl`, `cpu_boost` or
  `adreno_idler`.
- **The vivo display-mode lock writes only a mode the panel reported**
  (2026-10-20, booster cross-check). The reference locks vivo/iQOO panels by
  writing a hardcoded `user_preferred_display_mode_id 4` — its author's id for
  one 1080x2400@120 panel, and mode ids are display-driver facts: the same id
  on another panel is a different mode, or no mode at all. `VivoDisplayMode`
  instead enumerates `Display.getSupportedModes()` and picks the largest mode at
  the rate the app already measured as the panel's peak
  (`RefreshRateController.peakHz`), writing only when that mode is not already
  in effect; a panel with no mode at that rate writes nothing and reports the
  refusal. The companion write (`vivo_screen_refresh_rate_mode`, the vendor
  switch OriginOS honours over the standard rate keys on some builds) is
  recorded before activation and restored like every other setting — the
  reference's revert writes `-1` for an absent prior, while this app's snapshot
  restores "the key was not set" as `settings delete`. Gated on
  `isVivoOrIqoo()`: on any other device the lock is not applicable, never a
  silent success.

## Game files (File Engineering)

- **Every write: back up the device's bytes → push → read-back verify**
  (2026-10-17). The backup is the bytes the device *held*; a channel that
  cannot read the current file backs up nothing rather than the wrong thing.
  `md5sum` for bytes, never `cat`.
- **PUBG saves are byte-patched, never replaced** (2026-09-20): targeted
  `IntProperty` rewrites, all-or-nothing. A full GVAS serializer can corrupt a
  real save on one quirk.
- **WuWa Deploy History and Game Log Analysis are permanently removed**
  (2026-10-16, product requirement): deploys are not recorded anywhere — the
  deploy report is the record. `WuwaRemovedFeaturesTest` bans the entry
  points from returning. The tuner and installed-profile readers still read
  `Client.log` for their own measured axes; that is not the removed surface.
- **WuWa deploys go through one `DeviceChannel`** (2026-10-17): the
  root/Shizuku `ShellChannel` owns backup → push → md5 verify → hash refresh;
  SAF stays separate (no shell at all); wireless ADB was removed as a third
  channel.
- **Smart recommendations weigh only measured axes** (2026-10-17): log-only
  axes are named in `logOnlyAxes`, never silently scored as neutral, and the
  ladder never emits tiers that require unmeasured evidence.
- **Game editors are embedded, not separate screens** (2026-10-17):
  HSR/WuWa/GRID started as dashboard cards + standalone routes, spreading one
  job over four screens; they are now `embeddedEditor = true` entries hosted
  inline by File Engineering.
- **Reference-authored bytes ship as profiles; invented bytes do not**
  (2026-10-19, M190/M191 note that argues with the earlier "whole-template
  push stays rejected" text): that rejection barred *our own* re-encoded
  `+CVars=` blobs (meanings unverified), not the reference tools' own working
  constants. The eu GFX tool's `UserCustom.ini` template — decoded from its
  string table and checked against both pinned corpora — now ships as a third
  PUBG profile under the developer's ship-then-field-fix posture. The push
  merges: everything from `[BackUp DeviceProfile]` on stays the device's own
  tail (`PubgUserCustom.mergePreservingBackup`) and the previous file always
  goes to `ConfigBackupStore` first.
- **Profiles carry their own target file** (2026-10-19, M190/M191):
  `GameProfile.targetDir`/`targetFile` exist because `UserCustom.ini` lives in
  `Saved/Config/Android/`, not `SaveGames/`. Push, backup key and restore
  target all resolve from the profile; a restore derives its destination from
  the backup entry's own file name, so `UserCustom.ini` bytes can never be
  restored into `Active.sav`. `performSafFileCopy` keeps its direct semantics
  (the user picks the folder that contains the file; only the file name is
  substituted). ZARCHIVER is hidden for preserve profiles: a manual copy
  cannot read the current file, so it cannot keep the backup section.
- **Root-only configs bypass the method chooser** (2026-10-19, M168):
  PUBG: New State's `GameUserSettings.ini` lives in another app's private data
  directory — no SAF grant and no Shizuku shell reaches it — so
  `GameConfig.rootOnlyIniPath` routes APPLY straight to root
  (`NewStateIniTransform`), the restore chooser offers only the root channel,
  and the profile card carries the requirement as a caption. The transform
  replaces the whole `InGameSetting=` line with the reference's tuple verbatim
  (the module's `s/InGameSetting=*/` prefix rewrite would leave a stale tuple
  dangling behind the new one) and raises `FrameRateLimit` only, never lowers
  it (no-caps rule). The module's concealment lines stay out (M170).
- **The folder picker gets a starting point; the file picker stays plain**
  (2026-10-19, M200): `LaunchFolderPicker.initialUri` carries the
  version-branched `EXTRA_INITIAL_URI` built by `PubgSafAccess` (U+200B `data`
  segment above API 33, plain form 30–33, null below); the hint only moves the
  picker's initial view — the grant is exactly what the user confirms.
  `LaunchSafPicker` deliberately remains a `data object` with no starting URI,
  so the push picker's scope cannot widen by accident.

## Infrastructure & UI

- **Display facts have exactly two owners**: `DisplayMetricsProvider`
  (resolution/density) and `DisplayRefreshRateProvider` (refresh). Resolution
  and refresh rate were once read through different APIs that quoted different
  numbers for the same screen.
- **Notification IDs are inventoried** (2026-10-17): every new ID is checked
  against the list in [CODING_STYLE.md](CODING_STYLE.md) — the skipped-sweep
  notice once shared the overlay's ID 104.
- **The Magisk skeleton is generated, not checked in** (2026-10-17): the
  checked-in `module.prop` had gone stale (1.0.0/5 vs the shipped 2.0.0/6);
  version values now come from `BuildConfig` and the module shape is pinned by
  `MagiskModuleBuilderTest`.
- **`app_prefs` ≠ `AppPrefs`**; new features get their own prefs file — read
  existing keys before introducing new ones.
- **WorkManager's default initializer is removed** from the manifest;
  `CatsmokerApp` is the `Configuration.Provider` (HiltWorkerFactory). New
  schedules follow `DexoptSweepScheduler`: unique periodic work with `UPDATE`.
- **Corrupt incremental Kotlin state** (`shrunk-classpath-snapshot.bin does
  not exist` + bogus same-package unresolved references) is fixed by deleting
  `app/build/kotlin/compileFullDebugKotlin` and rebuilding — not by code
  changes.
- **Overlay positions are anchored per overlay, not unified** (2026-10-18,
  M49/V16): the crosshair keeps an offset from screen centre
  (`CrosshairPositionStore`) because it is symmetric about its window and the
  centre is the anchor that survives `wm size`/`wm density` edits by this app's
  own Resolution Changer; the performance readout keeps absolute top-left pixels
  (`PerformanceOverlayPositionStore`, `overlay_prefs`) because its historic
  default is (100, 100) and a corner anchor preserves that pixel meaning — a
  centre-relative store would have silently re-centred a corner-parked readout.
  Both clamp against the live display on read, so a shrunk panel cannot strand
  either off-screen; the differing conventions are deliberate, not drift.
  Dragging is a mode on both (the window is `FLAG_NOT_TOUCHABLE` outside it, so
  taps otherwise reach the game), the service broadcasts its own mode changes,
  and the UI follows that report — never the request.
- **The Unity frame-rate delivery loads the bridge through the module class
  loader** (2026-10-19, amended 2026-10-20). `System.loadLibrary` resolves
  through the calling class's loader, and the LSPosed/Vector module loader
  implements `findLibrary` — it answers the packaged zip path
  (`<apk>!/lib/<abi>/libcatsmoker_bridge.so`, which requires the `.so` to be a
  STORED zip entry), so `UnityFrameRateDelivery` tries that route first. The
  route matters for what the load *triggers*: the framework's native module API
  watches the registered library and calls its `native_init`, so the M152
  channel and the native spoof channel arrive in one copy. Extraction into the
  target's cache + `System.load` remains only as the fallback for a loader that
  cannot answer `findLibrary` — that route maps a second, unregistered copy. The
  native call is posted to the target's main thread (the Unity engine loop) and
  gated by a non-blocking `/proc/self/maps` probe, because calling an IL2CPP
  icall from a raw thread segfaults on Unity 6 (device-proven). The resolver
  address is read from the library's *executable* mapping, not the first
  offset-0 line: Android 15 keeps a whole-file `r--p` duplicate of
  `libil2cpp.so` at offset 0, and calling through it faulted at the resolver
  entry (POCO X3, 2026-10-20). Two limits found on that run remain open in M152:
  a mapped-but-uninitialized `libil2cpp.so` in a helper process
  (`com.mobile.legends:UnityKillsMe`) makes `il2cpp_resolve_icall` dereference
  null, and a Unity 6000.3 title raised a managed exception that null-derefs
  inside IL2CPP when the getter was called from the main thread — so "the main
  thread is engine-attached" is not true on every Unity build. The native spoof
  hooks are unaffected: they had installed and probed before either fault.
  Raise-only: the current value is read first and only `0 < current < peak` is
  raised; Unity's `-1` is untouched. The reference's Zygisk-module shape and its
  FPS-cap targets remain rejected.
- **Diagnostics are one on-demand ZIP, never a recording pipeline**
  (2026-10-19, M228): the crash chain (`CrashLogStore`) plus the export action
  (`BugReportExporter`) ship; the reference's exit history stays removed by
  design, no network client is instrumented, and the manual screenshot remains
  the user-visible capture. The bundle is built only when the user exports it -
  no background capture beyond the crash record itself.

## Backlog closure (2026-10-19)

The developer asked for the local capability audit (`TODO.md`) to be finished
and the file removed. Every open row was re-checked against current source;
this section is the durable record that replaces the file. Two real gaps were
implemented with reference code; everything else was already covered,
deliberately not ported, or blocked by hardware/field evidence.

- **Implemented now (reference code):**
  - **M12** — GRID Autosport's `FeralUseFXAA` and `gfxconfig_high_window_scale`
    are editable by exact name (`GridPreferences.ReadResult.fxaa/windowScale`
    and `Edits.fxaa/windowScale`) and rendered in the performance-switches
    card. The `Feral*FPSCap` binary doubles stay game-managed because every one
    is a ceiling. Reference: the shipped `preferences` file in
    `Change-Grid-Autosport...` (keys absent from that repo's README list).
  - **M106(b)** — `WuwaConfigManager.cleanShaderCache` implements the
    reference's Protocol A: force-stop, delete
    `files/UE4Game/{ProgramBinaryCache,VulkanProgramBinaryCache}`, read-back
    with `test ! -e`, deleted/absent/refused kept distinct, root/Shizuku only;
    surfaced by the deploy card's Clear-shader-cache button.
- **Closed — already covered / satisfied alternative:** every "Different
  implementation" row (M6, M37, M52, M57, M59, M75, M85, M103, M109, M129,
  M132, M139, M149, M153, M159, M161, M167, M172, M187, M189, M213, M217,
  M220, M223, M224, M226, M227, M230, M238, M242, M254, M257) plus M45 (session
  snapshot + pointer speed; per-game pct sliders add UI, no new mechanism), M48
  (the 3x5s/500ms retry values are already in `ShellRunner`), M67 (preset
  stores shipped; terminal store dead upstream), M68 (gaming levers ported; dev
  toggles correctly absent), M71 (`ShellRunner` channel richer; truncate/grant
  unneeded), M79 (custom upload exists; reference folder executors are stubs),
  M92 (retune math + `WuwaSmartBrain` + tuner cover it), M99 (root/Shizuku/SAF
  covered; ADB rejected), M100 (deploy report + backup diff + report row ship;
  device-side pairing is field verification), M101 (`cvarOverrides` ships;
  `importFromLog` permanently removed; `useAdvancedGen` not ported because
  `WuwaSmartBrain` already scores that decision), M110 (provider triple-surface
  without the caller-asserted bridge), M126 (measured-peak debug-only port; the
  persist twin has the wrong lifetime), M138 (rate ladders + step-down ship),
  M158 (mechanism via assignments + ladders; table values stay rejected), M198
  (brightness/rotation + the force-stop service match the fragment), M229 (no
  gap), M255/M256 (compliant ART subset and the metric itself already ship).
- **Closed — deliberately not ported (decision):** M24 (Zygisk limiter; caps
  barred), M127/M147's Vulkan half (re-decided 2026-10-20: the native half is now
  implemented — `NativeSpoofChannel` + `jni/catsmoker_spoof.c`; the Vulkan
  device string would need a profile key nobody authored, and the "G4, no
  LSPosed-OUT surface" claim is retracted — see "Spoofing & device identity"),
  M131
  ABI/Zygisk installer gates (no native payload), M142/M155 `lock_fps` and
  fps:0 limiter semantics (caps barred; M152 ships the raise-only staging), M143
  zero-manager/Zygisk-activity gates (installer-unreachable / no native
  injection), M145 tag layer (declined with P5), M148 comfort tweaks (stay
  session-scoped per P7/P8), M156 Houdini path (no x86 titles targeted), M178
  powerhint values (wrong-node/sustained caps on the one audited device), M204
  (upstream marks it broken; folds into the unbuilt M190 encoder).
- **Closed — blocked by hardware/field evidence (no code owed):** M50 wakelock
  (battery evidence), M58 analysis worker (its M55 reference row no longer
  exists; manual sweep + periodic schedule already mirrored), M106(a) `sg.*`
  bypass (needs before/after log evidence), M152/M168/M190/M191/M200
  verification tails (no capped Unity title / no launchable New State / no
  A11–13 PUBG device / no induced crash), M184 Samsung service surface (no
  Samsung device and no app channel), M253 `list-configs`/`list-modes` probe
  (the shipped levers already carry refusal text and no mode-choice surface
  consumes a probe — amended 2026-10-19: both reads are now consumed as
  read-back in the game-mode and `cmd game set` fallback paths; the
  no-mode-picker half stands), §3 F1 thermal sysfs, F3 data-danger proofs, F4 ban-risk
  review, and the SoC/vendor node matrix.
- **§5 V3** stays closed (no correlation surface after the H56 removal);
  **§6 G1–G4** are permanent statements, not backlog items; **D2 LSPatch**
  remains a product-gated decision, not an implementation gap.
- The local `TODO.md` was deleted after this pass at the developer's request;
  this section is its durable record. Field reports still drive fixes for the
  shipped-unverified work (M152, M168, M190, M200, M228).
