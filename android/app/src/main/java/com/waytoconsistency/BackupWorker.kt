package com.waytoconsistency

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val latest = File(applicationContext.filesDir, "backups/latest.json")
        if (!latest.isFile || latest.length() == 0L) return Result.success()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
        val name = "way-to-consistency-$stamp.json"
        val bytes = try { latest.readBytes() } catch (_: Exception) { return Result.retry() }
        return if (writePrivateBackup(name, bytes) && writeBackup(name, bytes)) Result.success() else Result.retry()
    }

    private fun writePrivateBackup(name: String, bytes: ByteArray): Boolean {
        return try {
            val directory = File(applicationContext.filesDir, "backups/history")
            if (!directory.exists() && !directory.mkdirs()) return false
            File(directory, name).writeBytes(bytes)
            directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.drop(MAX_BACKUPS)?.forEach(File::delete)
            true
        } catch (_: Exception) { false }
    }

    private fun writeBackup(name: String, bytes: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/WayToConsistency/Backups")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                try {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                    prunePublicBackups()
                    true
                } catch (error: Exception) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            } else {
                val directory = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Backups")
                if (!directory.exists() && !directory.mkdirs()) return false
                File(directory, name).writeBytes(bytes)
                directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.drop(MAX_BACKUPS)?.forEach(File::delete)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun prunePublicBackups() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/WayToConsistency/Backups/", "way-to-consistency-%.json")
        val old = mutableListOf<android.net.Uri>()
        resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                if (old.size >= MAX_BACKUPS) resolver.delete(uri, null, null) else old.add(uri)
            }
        }
    }

    companion object {
        private const val MAX_BACKUPS = 8
        private const val PERIODIC_NAME = "weekly-local-backup"

        fun latestBackupText(context: Context): String? = try {
            val directory = File(context.filesDir, "backups/history")
            directory.listFiles()?.filter { it.isFile && it.extension == "json" }?.maxByOrNull { it.lastModified() }?.readText()
                ?: File(context.filesDir, "backups/latest.json").takeIf { it.isFile }?.readText()
        } catch (_: Exception) { null }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun createNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("local-backup-now", ExistingWorkPolicy.REPLACE, request)
        }
    }
}