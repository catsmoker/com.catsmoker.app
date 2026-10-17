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
- **`StoreData` collections are read-only** (2026-09-23). In-place mutation
  made a new snapshot compare equal to the old one and swallowed the UI update.
- **A partial spoof is worse than none.** Device-info readers use both
  `SystemProperties` and a shelled `getprop`; hooking one channel creates a
  disagreement that is a stronger signal than real values. Hook both or
  neither.
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
