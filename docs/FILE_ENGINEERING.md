# File Engineering (game config editors)

How CatSmoker edits game-side files, and the rules each editor follows.
Owned by `features/editgamefiles/`: `EditGameFilesScreen` +
`EditGameFilesViewModel` (game registry, `GameProfile.labelRes`) plus one
package per game — `genshin/`, `grid/`, `hsr/`, `pubg/`, `wuwa/`, `service/`
(shared backup/SAF plumbing).

HSR, WuWa and GRID are **embedded editors** (`embeddedEditor = true`): they are
hosted inline by File Engineering and have no navigation route. Never push a
bundled template over an embedded editor's screen.

## The write discipline (every game, every channel)

1. **Back up first.** `ConfigBackupStore` keeps the *device's* bytes (10
   backups per game). A channel that cannot read the current file backs up
   nothing rather than the wrong thing.
2. **Push** through the available channel — root/Shizuku shell, or SAF /
   hand-moved export for the non-privileged path. There is no wireless ADB
   channel (removed 2026-09-20; do not re-add).
3. **Read back and verify.** `md5sum` on device for byte exactness — never
   `cat` for bytes. A write is not done until the device has confirmed it.

## Per-game rules

- **Genshin** (`genshin/GenshinConfigTemplate`): `hardware_model_config.json`
  is *deliberately invalid JSON* (the game tolerates it) — text substitution
  only, never a JSON parser. The model placeholder is substituted at delivery
  with the device's real `Build.MODEL`.
- **GRID** (`grid/GridPreferences`): Feral registry XML — targeted `<value>`
  substitution only, never whole-file replacement (the file carries binary
  blobs and hidden fields). The editable set is the README's keys plus the
  shipped file's `FeralUseFXAA` and `gfxconfig_high_window_scale` (exact name,
  the file's own integer type); the `Feral*FPSCap` binary doubles stay
  game-managed — every one is a ceiling and this editor only raises.
- **PUBG** (`pubg/`): `Active.sav` GVAS **byte-patch** (`PubgSavePatcher`,
  `IntProperty` only, all-or-nothing — any unrecognized field refuses the
  whole edit). Three profile pushes: Max FPS, tablet view, and the eu GFX
  tool's own `UserCustom.ini` template (`PubgUserCustom`) — the last merges,
  so the device's `[BackUp DeviceProfile]` tail survives and the previous file
  always goes to `ConfigBackupStore` first. Shipped saves under `assets/PUBG/`
  double as test fixtures. **PUBG: New State is a separate registry entry**:
  root-only, because its `GameUserSettings.ini` lives in the game's private
  data directory; APPLY runs `NewStateIniTransform` in place (reference tuple
  verbatim, frame rate raise-only, `chmod 0440` lock, read-back) with no
  method chooser. The folder picker gets a version-branched
  `Android/data/<package>` starting point (`PubgSafAccess`) where the platform
  accepts one — selection and grant stay the user's.
- **HSR** (`hsr/`): `HsrGraphicsSettings` (URL-encoded JSON codec),
  `HsrPlayerPrefsXml`, `HsrGameManager` (path templates; backup → cp → chmod →
  read-back), `HsrGamePreferences`.
- **WuWa** (`wuwa/`): `WuWaConfigGenerator` + `WuwaForbiddenCvars` (verbatim
  key list) + `WuwaSmartBrain`/`WuwaBenchmarkTuner` (measured-evidence axes
  only; unmeasured axes are named in `logOnlyAxes`, never scored neutral) +
  `WuwaLogDecryptor`/`WuwaLogParser` + `WuwaConfigManager` (`DeviceChannel`:
  shell = backup → push → md5 verify → hash refresh; SAF = no shell, no
  force-stop, no hash sync) + `WuwaProfileExtractor` + `WuwaCommunityPack` +
  `EditHistory`. `WuwaConfigManager.cleanShaderCache` implements the
  reference's Protocol A cache delete (`files/UE4Game/ProgramBinaryCache` and
  `VulkanProgramBinaryCache`): force-stop first, `rm -rf`, read-back with
  `test ! -e`, deleted/absent/refused kept distinct, root/Shizuku only —
  surfaced by the deploy card's Clear-shader-cache button.

## WuWa product exclusions (permanent)

**Deploy History and Game Log Analysis are permanently removed** (product
requirement, 2026-10-16). Deploys are not recorded anywhere; the deploy report
is the record. `WuwaRemovedFeaturesTest` bans the entry points, stores and
strings from returning. The tuner and installed-profile readers still read
`Client.log` internally for their own measured axes — that is not the removed
surface. Do not re-add either surface in any form.

## UI rules here

- The universal card rules live in `CODING_STYLE.md` (no informational-only
  cards; always-show-gray). The game editors add: confirmations are toasts, and
  warnings that affect the next action (e.g. the HSR external-change warning)
  sit as a line above the action. Removal precedent for both editors is noted
  in `CODING_STYLE.md`.

## Pitfalls

- WuWa SQLite reads quote identifiers (`"value"`, `"key"` are reserved words).
- WuWa log axes need a decrypted `Client.log`; without it, say so — never
  guess values.
- The game selector opens a per-game gate (installed-probe); "show anyway"
  exists for WuWa so the editor is reachable without the game installed.

## Tests

Every codec and patcher has a JVM test class under
`app/src/test/java/com/catsmoker/app/features/editgamefiles/`. The behavioural
locks worth naming: `PubgSavePatcherTest` (GVAS + the bundled real saves), `PubgUserCustomTest` (merge shape against both pinned corpora), `NewStateIniTransformTest` (tuple + raise-only), `PubgSafAccessTest` (URI shapes),
`GenshinConfigTemplateTest`, `GridPreferencesTest`,
`HsrGraphicsSettingsTest`/`HsrGraphicsPresetTest`, `WuWaConfigGeneratorTest`,
`WuwaPresetSelectionTest` (selection semantics), `WuWaSmartBrainTest`,
`WuwaRemovedFeaturesTest` (the WuWa exclusion), `EditHistoryTest`,
`InstallProbesTest`. `LocaleParityTest` (in `src/test/.../shared/`) guards
resource parity across the five locale folders.
