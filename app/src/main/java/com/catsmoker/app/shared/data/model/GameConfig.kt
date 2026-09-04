package com.catsmoker.app.shared.data.model

/**
 * One editable thing about a game, as the Edit Game Files screen offers it.
 *
 * A profile pairs the label the user picks with the bundled asset that carries it. PUBG has two
 * (max-FPS and iPad-view saves); Genshin Impact has one (the tuned `hardware_model_config.json`).
 */
data class GameProfile(
    val label: String,
    val assetPath: String
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
    val requiresDeviceModel: Boolean = false
)
