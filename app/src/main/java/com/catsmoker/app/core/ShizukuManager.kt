package com.catsmoker.app.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.service.CommandRunnerService
import com.catsmoker.app.shizuku.ICommandRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class ShizukuManager(private val context: Context) {

    private val _isShizukuAvailable = MutableStateFlow(false)
    val isShizukuAvailable: StateFlow<Boolean> = _isShizukuAvailable.asStateFlow()

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    @Volatile
    private var commandRunner: ICommandRunner? = null
    private var pendingConnection: CompletableDeferred<ICommandRunner?>? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _isShizukuAvailable.value = true
        checkPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _isShizukuAvailable.value = false
        _hasPermission.value = false
        commandRunner = null
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        _isShizukuAvailable.value = Shizuku.pingBinder()
        if (_isShizukuAvailable.value) {
            checkPermission()
        }
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    fun checkPermission() {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            _hasPermission.value = false
            return
        }
        _hasPermission.value = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission() {
        if (!Shizuku.isPreV11() && Shizuku.getVersion() >= 11) {
            Shizuku.requestPermission(1001)
        }
    }

    private suspend fun getRunner(): ICommandRunner? {
        commandRunner?.let { return it }
        
        val deferred = CompletableDeferred<ICommandRunner?>()
        pendingConnection = deferred

        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, CommandRunnerService::class.java.name))
            .daemon(true)
            .processNameSuffix("runner")
            .version(BuildConfig.VERSION_CODE)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val runner = ICommandRunner.Stub.asInterface(service)
                commandRunner = runner
                deferred.complete(runner)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                commandRunner = null
            }
        }

        try {
            Shizuku.bindUserService(args, connection)
            return withTimeoutOrNull(5000L) { deferred.await() }
        } catch (e: Exception) {
            return null
        }
    }

    suspend fun executeCommand(command: String): String {
        if (!_isShizukuAvailable.value || !_hasPermission.value) return ""
        return withContext(Dispatchers.IO) {
            try {
                getRunner()?.executeCommand(command) ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }

    suspend fun executeCommandWithResult(command: String): com.catsmoker.app.shizuku.CommandResult? {
        if (!_isShizukuAvailable.value || !_hasPermission.value) return null
        return withContext(Dispatchers.IO) {
            try {
                getRunner()?.executeCommandWithResult(command)
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun killCurrentProcess() {
        withContext(Dispatchers.IO) {
            try {
                getRunner()?.killCurrentProcess()
            } catch (_: Exception) {}
        }
    }

    suspend fun getThermalTemperatures(): String {
        if (!_isShizukuAvailable.value || !_hasPermission.value) return ""
        return withContext(Dispatchers.IO) {
            try {
                getRunner()?.getThermalTemperatures() ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }
}
