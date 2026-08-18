package com.catsmoker.app.system.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.catsmoker.app.IFileService
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import kotlin.time.Duration.Companion.milliseconds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShellRunner @Inject constructor(
    @ApplicationContext private val context: Context
) : Shizuku.OnRequestPermissionResultListener {
    private val _shizukuHasPermission = MutableStateFlow(false)
    val shizukuHasPermission = _shizukuHasPermission.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var cachedRootAvailable: Boolean? = null
    private var lastRootCheckTime = 0L
    private val rootCheckCooldown = 2000.milliseconds

    private var fileService: IFileService? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuPermission()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
        _shizukuHasPermission.value = granted
        if (granted) {
            scope.launch {
                delay(500.milliseconds)
                ensureServiceBound()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            fileService = IFileService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileService = null
        }
    }

    init {
        // Safe check to avoid duplicate listeners if singleton is recreated
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.removeRequestPermissionResultListener(this)
            Shizuku.addRequestPermissionResultListener(this)
            
            // Initial check
            refreshShizukuPermission()
        } catch (_: Throwable) {}
    }

    fun isRootAvailable(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && cachedRootAvailable != null && (now - lastRootCheckTime) < rootCheckCooldown.inWholeMilliseconds) {
            return cachedRootAvailable!!
        }
        
        // Use a more robust check if forced or cache expired
        val rooted = try {
            Shell.getShell().isRoot
        } catch (_: Exception) {
            Shell.isAppGrantedRoot() == true
        }
        
        cachedRootAvailable = rooted
        lastRootCheckTime = now
        return rooted
    }

    fun hasPrivilege(): Boolean = isRootAvailable() || _shizukuHasPermission.value

    fun refreshShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            _shizukuHasPermission.value = false
            return
        }
        try {
            val granted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            _shizukuHasPermission.value = granted
            if (granted) {
                scope.launch {
                    delay(500.milliseconds)
                    ensureServiceBound()
                }
            }
        } catch (_: Throwable) {
            _shizukuHasPermission.value = false
        }
    }

    private fun ensureServiceBound() {
        if (fileService != null) return
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, "com.catsmoker.app.features.editgamefiles.service.FileService"))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(true)
        try {
            Shizuku.bindUserService(args, serviceConnection)
        } catch (_: Throwable) {}
    }

    suspend fun trimCaches() {
        exec("pm trim-caches 4G")
    }

    /**
     * Executes a command with arguments safely escaped.
     */
    suspend fun execSafe(vararg args: String): String {
        val command = args.joinToString(" ") {
            // Basic shell escaping for common paths
            if (it.contains(" ") || it.contains("\"") || it.contains("'")) {
                "\"" + it.replace("\"", "\\\"") + "\""
            } else {
                it
            }
        }
        return exec(command)
    }

    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        // 1. Try Root
        if (isRootAvailable()) {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) return@withContext result.out.joinToString("\n")
            else Log.e("ShellRunner", "Root exec failed: ${result.err.joinToString("\n")}")
        }

        // 2. Try Shizuku Service
        val service = getFileService()
        if (service != null) {
            try {
                return@withContext service.executeAndGetOutput(arrayOf("sh", "-c", command)).joinToString("\n")
            } catch (e: Throwable) {
                Log.e("ShellRunner", "Shizuku service exec failed", e)
            }
        }

        // 3. Fallback to normal shell
        val result = Shell.cmd(command).exec()
        if (!result.isSuccess) {
            Log.w("ShellRunner", "Shell exec failed: ${result.err.joinToString("\n")}")
        }
        result.out.joinToString("\n")
    }

    private suspend fun getFileService(): IFileService? {
        if (fileService != null) return fileService
        if (!_shizukuHasPermission.value) return null
        
        return withTimeoutOrNull(1000.milliseconds) {
            while (fileService == null && isActive) {
                ensureServiceBound()
                delay(100.milliseconds)
            }
            fileService
        }
    }

    suspend fun run(command: String): Shell.Result = withContext(Dispatchers.IO) {
        Shell.cmd(command).exec()
    }

    suspend fun readThermal(): String = exec("dumpsys thermalservice")

    fun killCurrentProcess() {
        // Implementation for killing in-flight processes if needed
    }
}
