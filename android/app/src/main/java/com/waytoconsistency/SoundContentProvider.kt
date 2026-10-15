package com.waytoconsistency

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

class SoundContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = soundFile(uri)?.extension?.let { extension ->
        when (extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "3gp" -> "audio/3gpp"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val file = soundFile(uri) ?: throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun soundFile(uri: Uri): File? {
        val id = uri.lastPathSegment ?: return null
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return null
        val directory = File(requireNotNull(context).filesDir, "reminder-sounds")
        return directory.listFiles()?.firstOrNull { it.name.substringBefore('.') == id && it.isFile && it.length() > 0 }
    }
}

object SoundStorage {
    fun uri(context: android.content.Context, spec: String): Uri? {
        if (!spec.startsWith("audio:")) return null
        val id = spec.removePrefix("audio:")
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return null
        val directory = File(context.filesDir, "reminder-sounds")
        val file = directory.listFiles()?.firstOrNull { it.name.substringBefore('.') == id && it.isFile && it.length() > 0 } ?: return null
        return Uri.parse("content://${context.packageName}.sounds/sound/${Uri.encode(id)}")
    }
}