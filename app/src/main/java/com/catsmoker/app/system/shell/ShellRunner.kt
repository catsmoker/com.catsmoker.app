package com.catsmoker.app.system.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.IFileService
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Single entry point for privileged execution. Prefers root, falls back to a Shizuku
 * user service, then to an unprivileged shell.
 */
@Singleton
class ShellRunner @Inject constructor(
    @ApplicationContext private val context: Context
) : Shizuku.OnRequestPermissionResultListener {

    /** Outcome of a command, including the exit code so callers can tell success from silence. */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0

        /** stdout when present, otherwise stderr — for surfacing a single line to the user. */
        val text: String get() = stdout.ifBlank { stderr }

        companion object {
            val FAILED = ExecResult(-1, "", "")
        }
    }

    private val _shizukuHasPermission = MutableStateFlow(false)
    val shizukuHasPermission = _shizukuHasPermission.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    @Volatile
    private var lastRootCheckTime = 0L

    @Volatile
    private var fileService: IFileService? = null

    /** Non-null only while a bind is in flight; concurrent callers await the same result. */
    private var pendingBind: CompletableDeferred<IFileService?>? = null
    private val bindMutex = Mutex()

    /** Live connection object; used as an identity token so a stale callback cannot clobber it. */
    @Volatile
    private var activeConnection: ServiceConnection? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshShizukuPermission() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // Shizuku went away: the user service died with it, so drop the stale proxy.
        fileService = null
        activeConnection = null
        _shizukuHasPermission.value = false
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        _shizukuHasPermission.value = granted
        if (!granted) return
        // Root outranks Shizuku, so do not spawn the helper process when root can already do the
        // work. execResult() binds lazily if root turns out to be unavailable later.
        scope.launch(Dispatchers.IO) { if (!isRootAvailable()) bindUserService() }
    }

    init {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            // Sticky: fires immediately if the binder arrived before this singleton was created.
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(this)
            Shizuku.addRequestPermissionResultListener(this)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- privileges

    fun isRootAvailable(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedRootAvailable
        if (!force && cached != null && (now - lastRootCheckTime) < ROOT_CHECK_COOLDOWN_MS) {
            return cached
        }

        val rooted = try {
            Shell.getShell().isRoot
        } catch (_: Throwable) {
            Shell.isAppGrantedRoot() == true
        }

        cachedRootAvailable = rooted
        lastRootCheckTime = now
        return rooted
    }

    fun hasPrivilege(): Boolean = isRootAvailable() || _shizukuHasPermission.value

    fun refreshShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            // Shizuku can be killed without the binder-dead callback ever firing.
            fileService = null
            activeConnection = null
            _shizukuHasPermission.value = false
            return
        }
        try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            _shizukuHasPermission.value = granted
            if (!granted) {
                fileService = null
                return
            }
            // The root probe forks a shell, so it must not run on the caller's thread.
            scope.launch(Dispatchers.IO) { if (!isRootAvailable()) bindUserService() }
        } catch (_: Throwable) {
            _shizukuHasPermission.value = false
        }
    }

    // ------------------------------------------------------------- user service

    private fun userServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, FILE_SERVICE_CLASS)
    )
        // daemon(true) keeps the helper alive across app restarts, so later binds are instant.
        .daemon(true)
        .processNameSuffix("service")
        .tag(USER_SERVICE_TAG)
        // Bumping versionCode forces Shizuku to restart the helper instead of reusing an
        // old process whose AIDL no longer matches ours.
        .version(BuildConfig.VERSION_CODE)
        .debuggable(BuildConfig.DEBUG)

    /**
     * Binds the user service, retrying a few times. Concurrent callers share one attempt.
     * @return the live proxy, or null when Shizuku is unavailable or the bind never completed.
     */
    private suspend fun bindUserService(): IFileService? {
        fileService?.let { if (Shizuku.pingBinder()) return it else fileService = null }
        if (!_shizukuHasPermission.value || !Shizuku.pingBinder()) return null

        val deferred: CompletableDeferred<IFileService?>
        var isOwner = false
        bindMutex.withLock {
            deferred = pendingBind ?: CompletableDeferred<IFileService?>().also {
                pendingBind = it
                isOwner = true
            }
        }
        // Someone else is already binding — just wait for their outcome.
        if (!isOwner) return deferred.await()

        try {
            var bound: IFileService? = null
            for (attempt in 1..MAX_BIND_RETRIES) {
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (activeConnection !== this) return
                        fileService = service?.let { IFileService.Stub.asInterface(it) }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (activeConnection !== this) return
                        fileService = null
                    }
                }
                activeConnection = connection

                val requested = try {
                    Shizuku.bindUserService(userServiceArgs(), connection)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "bindUserService threw on attempt $attempt", t)
                    false
                }

                if (requested) {
                    bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {
                        while (fileService == null) delay(BIND_POLL_MS)
                        fileService
                    }
                    if (bound != null) break
                }

                if (attempt < MAX_BIND_RETRIES) delay(BIND_RETRY_DELAY_MS)
            }
            if (bound == null) Log.w(TAG, "Shizuku user service did not bind after $MAX_BIND_RETRIES attempts")
            deferred.complete(bound)
            return bound
        } catch (t: Throwable) {
            deferred.complete(null)
            return null
        } finally {
            bindMutex.withLock { if (pendingBind === deferred) pendingBind = null }
        }
    }

    // ------------------------------------------------------------------ execute

    /** Joins [args] into a command line, quoting any argument that needs it. */
    private fun joinArgs(args: Array<out String>): String = args.joinToString(" ") { arg ->
        if (arg.isEmpty() || arg.any { it.isWhitespace() || it in SHELL_METACHARACTERS }) {
            "'" + arg.replace("'", "'\\''") + "'"
        } else {
            arg
        }
    }

    /** Executes a command with each argument safely quoted. */
    suspend fun execSafe(vararg args: String): String = exec(joinArgs(args))

    /** Executes a command with each argument safely quoted, reporting the exit code. */
    suspend fun execSafeResult(vararg args: String): ExecResult = execResult(joinArgs(args))

    suspend fun exec(command: String): String = execResult(command).stdout

    /**
     * Runs [command] through the best available channel and reports stdout, stderr and the
     * exit code. Prefer this over [exec] whenever success matters: many `settings put` and
     * `cmd` invocations succeed with completely empty stdout.
     */
    suspend fun execResult(command: String): ExecResult = withContext(Dispatchers.IO) {
        if (isRootAvailable()) {
            val result = runCatching { Shell.cmd(command).exec() }.getOrNull()
            if (result != null) {
                if (!result.isSuccess) Log.w(TAG, "Root exec failed (exit ${result.code}): $command")
                // Root is the highest privilege we have; retrying with less cannot help.
                return@withContext ExecResult(result.code, result.out.joinToString("\n"), "")
            }
        }

        bindUserService()?.let { service ->
            try {
                val remote = service.executeForResult(arrayOf("sh", "-c", command))
                return@withContext ExecResult(
                    exitCode = remote.exitCode,
                    stdout = remote.output.orEmpty(),
                    stderr = remote.error.orEmpty()
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Shizuku service exec failed, dropping proxy", t)
                fileService = null
            }
        }

        val result = runCatching { Shell.cmd(command).exec() }.getOrNull()
            ?: return@withContext ExecResult.FAILED
        if (!result.isSuccess) {
            // FLAG_REDIRECT_STDERR folds stderr into stdout, so result.err is empty here.
            Log.w(TAG, "Unprivileged shell failed (exit ${result.code}): $command")
        }
        ExecResult(result.code, result.out.joinToString("\n"), "")
    }

    suspend fun trimCaches() {
        exec("pm trim-caches 4G")
    }

    // ------------------------------------------------------------------ thermal

    private enum class ThermalStrategy { SYSFS_DIRECT, DUMPSYS, SERVICE_SYSFS, SHELL_SYSFS }

    /** Cached winning strategy; reset to null whenever it stops producing readings. */
    @Volatile
    private var resolvedThermalStrategy: ThermalStrategy? = null

    /** Discovered (type, temp) sysfs file pairs, cached after the first successful sweep. */
    @Volatile
    private var sysfsZones: List<Pair<File, File>>? = null

    /**
     * Reads thermal sensors, preferring whichever channel worked last time.
     * Direct sysfs comes first because it needs no privileges and no process fork.
     *
     * @return raw text for [com.catsmoker.app.features.main.engine.parsers.ThermalServiceParser],
     *   or an empty string when no channel produced anything.
     */
    suspend fun readThermal(): String = withContext(Dispatchers.IO) {
        resolvedThermalStrategy?.let { cached ->
            val output = runThermalStrategy(cached)
            if (output.isNotBlank()) return@withContext output
            // The cached path went quiet (SELinux change, HAL restart) — re-resolve next time.
            resolvedThermalStrategy = null
        }

        for (strategy in ThermalStrategy.entries) {
            val output = runThermalStrategy(strategy)
            if (output.isNotBlank()) {
                resolvedThermalStrategy = strategy
                return@withContext output
            }
        }
        ""
    }

    private suspend fun runThermalStrategy(strategy: ThermalStrategy): String = when (strategy) {
        ThermalStrategy.SYSFS_DIRECT -> readSysfsDirect()
        ThermalStrategy.DUMPSYS -> if (hasPrivilege()) exec("dumpsys thermalservice") else ""
        ThermalStrategy.SERVICE_SYSFS -> if (isRootAvailable()) {
            // Root can read the zones directly through SHELL_SYSFS; binding the Shizuku helper
            // would spawn a second privileged process for nothing.
            ""
        } else {
            runCatching { bindUserService()?.readSysfsThermal().orEmpty() }.getOrDefault("")
        }
        ThermalStrategy.SHELL_SYSFS -> if (hasPrivilege()) {
            exec(
                "for z in /sys/class/thermal/thermal_zone*; do " +
                    "echo \"\$(cat \$z/type 2>/dev/null):\$(cat \$z/temp 2>/dev/null)\"; done"
            )
        } else {
            ""
        }
    }

    private fun readSysfsDirect(): String {
        val zones = sysfsZones ?: discoverSysfsZones().also { sysfsZones = it }
        if (zones.isEmpty()) return ""
        val sb = StringBuilder()
        for ((typeFile, tempFile) in zones) {
            val type = runCatching { typeFile.readText().trim() }.getOrNull()?.ifEmpty { null } ?: continue
            val temp = runCatching { tempFile.readText().trim() }.getOrNull()?.ifEmpty { null } ?: continue
            sb.append(type).append(':').append(temp).append('\n')
        }
        if (sb.isEmpty()) sysfsZones = null // Permissions changed; rediscover next call.
        return sb.toString()
    }

    private fun discoverSysfsZones(): List<Pair<File, File>> = try {
        File("/sys/class/thermal")
            .listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name }
            ?.mapNotNull { zone ->
                val type = File(zone, "type")
                val temp = File(zone, "temp")
                if (type.canRead() && temp.canRead()) type to temp else null
            }
            .orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    // --------------------------------------------------------------- /proc/stat

    private enum class ProcStatStrategy { DIRECT, ROOT_SHELL, SERVICE, SHELL }

    @Volatile
    private var resolvedProcStatStrategy: ProcStatStrategy? = null

    /**
     * Reads /proc/stat, which SELinux hides from untrusted_app on most Android 10+ builds.
     *
     * Order is cheapest-and-highest-privilege first: a plain read when SELinux allows it, then a
     * root shell, then — only when there is no root — the Shizuku binder, which avoids a fork but
     * costs a helper process.
     *
     * @return the file contents, or an empty string when no channel can reach it.
     */
    suspend fun readProcStat(): String = withContext(Dispatchers.IO) {
        resolvedProcStatStrategy?.let { cached ->
            val output = runProcStatStrategy(cached)
            if (output.isNotBlank()) return@withContext output
            resolvedProcStatStrategy = null
        }

        for (strategy in ProcStatStrategy.entries) {
            val output = runProcStatStrategy(strategy)
            if (output.isNotBlank()) {
                resolvedProcStatStrategy = strategy
                return@withContext output
            }
        }
        ""
    }

    private suspend fun runProcStatStrategy(strategy: ProcStatStrategy): String = when (strategy) {
        ProcStatStrategy.DIRECT -> runCatching { File(PROC_STAT).readText() }.getOrDefault("")
        // exec() routes through root when it is available, so this is the root channel.
        ProcStatStrategy.ROOT_SHELL -> if (isRootAvailable()) exec("cat $PROC_STAT") else ""
        ProcStatStrategy.SERVICE -> if (isRootAvailable()) {
            ""
        } else {
            runCatching { bindUserService()?.readProcStat().orEmpty() }.getOrDefault("")
        }
        ProcStatStrategy.SHELL -> if (hasPrivilege()) exec("cat $PROC_STAT") else ""
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Best-effort cancellation of the long-running privileged work we spawn (the ART dexopt
     * sweep). Both libsu and the Shizuku binder call are blocking, so the only lever we have
     * is to kill the compiler process the platform forked on our behalf.
     */
    suspend fun killCurrentProcess() {
        if (!hasPrivilege()) return
        for (pattern in COMPILE_PROCESS_PATTERNS) {
            exec("pkill -f $pattern")
        }
    }

    private companion object {
        const val TAG = "ShellRunner"
        const val FILE_SERVICE_CLASS = "com.catsmoker.app.features.editgamefiles.service.FileService"
        const val USER_SERVICE_TAG = "catsmoker_file_service"
        const val ROOT_CHECK_COOLDOWN_MS = 2000L
        const val PROC_STAT = "/proc/stat"
        const val MAX_BIND_RETRIES = 3
        val BIND_TIMEOUT_MS = 5000.milliseconds
        val BIND_RETRY_DELAY_MS = 500.milliseconds
        val BIND_POLL_MS = 50.milliseconds
        val SHELL_METACHARACTERS = charArrayOf(
            '"', '\'', '$', '`', '\\', '!', '*', '?', '[', ']', '(', ')', '{', '}',
            '|', '&', ';', '<', '>', '~', '#'
        )
        val COMPILE_PROCESS_PATTERNS = listOf("dex2oat", "dex2oat64")
    }
}
