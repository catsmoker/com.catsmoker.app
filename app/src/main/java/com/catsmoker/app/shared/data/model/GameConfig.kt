package com.catsmoker.app.shared.data.model

import androidx.annotation.StringRes

/**
 * One editable thing about a game, as the Edit Game Files screen offers it.
 *
 * A profile pairs the label the user picks with the bundled asset that carries it. PUBG has
 * three (max-FPS and iPad-view saves, plus the `UserCustom.ini` device profile); Genshin Impact
 * has one (the tuned `hardware_model_config.json`); PUBG: New State has one whose asset is empty
 * because its file is transformed in place under root (see [GameConfig.rootOnlyIniPath]).
 *
 * The label travels as a string-resource ID, never a resolved string: these configs are built in
 * a ViewModel with the application context, whose locale is frozen at process start, and the
 * ViewModel survives the activity `recreate()` a language switch performs — a resolved label
 * would stay in the previous language no matter what the user picks. The screen resolves the
 * ID with `stringResource`, which always follows the current language.
 */
data class GameProfile(
    @StringRes val labelRes: Int,
    val assetPath: String,
    /**
     * Where this profile's file lands, as a path relative to external storage (same convention
     * as [GameConfig.saveDir]). Null keeps the config's own directory. The `UserCustom.ini`
     * profile needs this: its file is not the config's `Active.sav` and sits in the game's
     * `Saved/Config/Android/` folder instead of `SaveGames/` (TODO M190).
     */
    val targetDir: String? = null,
    /**
     * The file name this profile overwrites when it differs from [GameConfig.saveFile]. Also
     * keys the profile's backups, so a restore puts the right bytes back into the right file.
     */
    val targetFile: String? = null,
    /**
     * True when the push must merge with the file already on the device instead of replacing it
     * wholesale: the bundled asset is only the `[UserCustom DeviceProfile]` section, and the
     * device's own `[BackUp DeviceProfile]` tail is carried over — the merge the eu GFX tool
     * performs (TODO M190/M191). ZARCHIVER is never offered for these profiles: a manual copy
     * cannot read the current file, so it could not preserve that section.
     */
    val preserveDeviceProfileBackup: Boolean = false
)

data class GameConfig(
    val packageName: String,
    val saveDir: String,
    val saveFile: String,
    val profiles: List<GameProfile> = emptyList(),
    /**
     * True when the bundled asset is a text template that names the device it is being pushed to.
     *
     * Genshin Impact's `hardware_model_config.json` looks its entries up by the device's real
     * model, so the shipped template carries a placeholder that every delivery channel must
     * substitute with `Build.MODEL` before the file lands. Binary blobs (PUBG's `Active.sav`)
     * never need this and stay false.
     */
    val requiresDeviceModel: Boolean = false,
    /**
     * Absolute path of the file whose deletion reverts every push this screen made, or null when
     * the game has no resettable file.
     *
     * PUBG regenerates `Active.sav` from its own defaults on the next launch, so deleting it is
     * the clean revert for both profiles — the mechanism `reference/gamingtools/
     * BattleGrounds_GFX-main` exposes as "Reset Active.sav". Genshin Impact's config always
     * exists on a working install and the game does not rebuild it from defaults, so there is
     * no reset channel for it and the field stays null.
     */
    val resetFilePath: String? = null,
    /**
     * The file name the UI should show when talking about this game's reset, defaulting to
     * [saveFile]. Exists because the reset button and its confirmation dialog once hardcoded
     * "ACTIVE.SAV" and showed it for every game — including Genshin, whose file is
     * `hardware_model_config.json` and which has no reset at all (null [resetFilePath] hides
     * the button entirely).
     */
    val resetFileLabel: String = saveFile,
    /**
     * Whether the screen's Custom File Upload section applies to this game.
     *
     * Custom upload pushes an arbitrary user-picked file over the game's config — that only
     * makes sense where the file format is the game's own opaque save blob the user may have
     * obtained elsewhere: PUBG's `Active.sav`. Genshin's `hardware_model_config.json` is a
     * model-keyed template this app generates (and the game's lookup is exact), so a random
     * dropped-in file would just break the lookup — the bundled profile is the whole surface,
     * and the section is hidden for it.
     */
    val allowCustomUpload: Boolean = false,
    /**
     * Whether the screen shows the inline save editor for this game — read-modify-write of the
     * fields the save already carries, rather than pushing a bundled file over it.
     *
     * Only PUBG's `Active.sav` qualifies today: its GVAS layout is verified byte-level (see
     * [com.catsmoker.app.features.editgamefiles.pubg.PubgSavePatcher]), and the closed-source
     * reference tools improve exactly these ints inside the user's own save. Whole-file
     * templates (Genshin) stay false — there is nothing to patch, only a file to replace.
     */
    val supportsSavePatching: Boolean = false,
    /**
     * Absolute path of a file this config edits in place under root instead of pushing a bundled
     * asset to it — or null for the normal asset-push games.
     *
     * PUBG: New State's `GameUserSettings.ini` lives in the game's private data directory
     * (`/data/data/...`), which neither SAF nor Shizuku can reach, so this config has no
     * delivery channels at all: APPLY runs
     * [com.catsmoker.app.features.editgamefiles.pubg.NewStateIniTransform] over the bytes the
     * device already holds and writes them back through root. For such a config [saveDir] and
     * [saveFile] are labels only; the reset, custom-upload and save-editor flags stay false,
     * and the restore channel targets this same path.
     */
    val rootOnlyIniPath: String? = null
)
