package com.catsmoker.app.system.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import androidx.core.net.toUri
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.catsmoker.app.shared.data.repository.SpoofRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException

class SpoofConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.catsmoker.app.configprovider"
        const val FILE_NAME = "resolved_profile.conf"
        val CONFIG_URI: Uri = "content://$AUTHORITY/$FILE_NAME".toUri()
        const val COLUMN_CONTENT = "content"
        const val METHOD_GET_CONFIG = "get_config"
        const val QUERY_PACKAGE = "package"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SpoofConfigEntryPoint {
        fun repository(): SpoofRepository
    }

    private fun getRepository(): SpoofRepository {
        val appContext = context?.applicationContext ?: throw IllegalStateException("Context missing")
        val entryPoint = EntryPointAccessors.fromApplication(appContext, SpoofConfigEntryPoint::class.java)
        return entryPoint.repository()
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val requestedPackage = resolvePackageName(uri, selectionArgs?.getOrNull(0))
        val content = resolveConfig(requestedPackage) ?: return null
        
        return MatrixCursor(arrayOf(COLUMN_CONTENT)).apply {
            addRow(arrayOf(content))
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_CONFIG) return super.call(method, arg, extras)
        
        val requestedPackage = resolvePackageName(CONFIG_URI, arg)
        val content = resolveConfig(requestedPackage) ?: return null
        
        return Bundle().apply {
            putString(COLUMN_CONTENT, content)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (FILE_NAME != uri.lastPathSegment || "r" != mode) {
            throw FileNotFoundException("Unknown config uri: $uri")
        }
        
        val requestedPackage = resolvePackageName(uri, null)
        val content = resolveConfig(requestedPackage) ?: throw FileNotFoundException("No profile assigned")
        
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                    }
                } catch (ignored: IOException) {}
            }, "catsmoker-config-provider")
            writer.start()
            pipe[0]
        } catch (e: IOException) {
            throw FileNotFoundException(e.message)
        }
    }

    override fun getType(uri: Uri): String = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolveConfig(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return runBlocking {
            val repo = getRepository()
            val profile = repo.getProfileForPackage(packageName) ?: return@runBlocking null
            val data = repo.loadData()
            repo.renderConfig(profile, data.globalProperties)
        }
    }

    /**
     * Decides whose profile the current caller is allowed to see.
     *
     * The provider has to stay exported because the Xposed hooks read it from inside the target
     * app's process, under that app's UID. So a caller only ever gets its own profile — otherwise
     * any installed app could name packages one by one and enumerate the user's whole spoof setup.
     *
     * @return the package whose profile to render, or null when the caller asked for someone else's.
     */
    private fun resolvePackageName(uri: Uri, requestedPackage: String?): String? {
        val explicit = requestedPackage?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.getQueryParameter(QUERY_PACKAGE)?.trim()?.takeIf { it.isNotEmpty() }

        val callingUid = Binder.getCallingUid()
        // Our own UI resolves arbitrary packages to preview what a profile renders to.
        if (callingUid == Process.myUid()) return explicit ?: context?.packageName

        val caller = callingPackage?.takeIf { it.isNotEmpty() }
        if (explicit == null) return caller

        // Shared-UID apps may legitimately ask for a sibling: callingPackage only names one of them.
        val siblings = runCatching { context?.packageManager?.getPackagesForUid(callingUid) }
            .getOrNull().orEmpty()
        return explicit.takeIf { it == caller || it in siblings }
    }
}
