package com.catsmoker.app.features.gamingtools.tools.art

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import com.catsmoker.app.features.gamingtools.engine.parsers.DexoptStatusParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtOptimizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gamingEngine: GamingEngine
) {
    private val isCancelled = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
    }

    suspend fun getDexoptStatuses(): Map<String, String> = withContext(Dispatchers.IO) {
        val output = gamingEngine.execute("dumpsys package dexopt")
        DexoptStatusParser.parse(output)
    }

    suspend fun compilePackage(packageName: String, mode: String, force: Boolean = false): Boolean {
        val forceFlag = if (force) "-f" else ""
        val cmd = "cmd package compile -m $mode $forceFlag $packageName"
        val output = gamingEngine.execute(cmd)
        return output.contains("Success")
    }

    suspend fun compileAllUserApps(mode: String, force: Boolean = false, onProgress: (String, Float) -> Unit) {
        isCancelled.set(false)
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }

        val statuses = if (!force) getDexoptStatuses() else emptyMap()

        apps.forEachIndexed { index, pkg ->
            if (isCancelled.get()) return
            
            if (!force && statuses[pkg] == mode) {
                onProgress(pkg, (index + 1).toFloat() / apps.size)
                return@forEachIndexed
            }
            compilePackage(pkg, mode, force)
            onProgress(pkg, (index + 1).toFloat() / apps.size)
        }
    }
}
