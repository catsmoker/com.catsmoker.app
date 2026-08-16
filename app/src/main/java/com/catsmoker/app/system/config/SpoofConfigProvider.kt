package com.catsmoker.app.system.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
        val CONFIG_URI: Uri = Uri.parse("content://$AUTHORITY/$FILE_NAME")
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

    private fun resolveConfig(packageName: String): String? = runBlocking {
        val repo = getRepository()
        val profile = repo.getProfileForPackage(packageName) ?: return@runBlocking null
        val data = repo.loadData()
        repo.renderConfig(profile, data.globalProperties)
    }

    private fun resolvePackageName(uri: Uri, requestedPackage: String?): String {
        if (!requestedPackage.isNullOrBlank()) return requestedPackage.trim()
        val queryPackage = uri.getQueryParameter(QUERY_PACKAGE)
        if (!queryPackage.isNullOrBlank()) return queryPackage.trim()
        return callingPackage ?: ""
    }
}
