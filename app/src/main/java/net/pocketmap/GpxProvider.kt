package net.pocketmap

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only, URI-granted access to GPX/KML exports, never to private recordings or other files. */
class GpxProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        if (uri.pathSegments.size != 2 || uri.pathSegments[0] != "export") throw FileNotFoundException()
        val name = uri.pathSegments[1]
        val validExtension = name.endsWith(".gpx") || name.endsWith(".kml")
        if (!validExtension || name.contains('/') || name.contains(92.toChar()) || name.contains("..")) throw FileNotFoundException()
        val directory = File(context!!.filesDir, "exports").canonicalFile
        val file = File(directory, name).canonicalFile
        if (file.parentFile != directory || !file.isFile) throw FileNotFoundException()
        return file
    }
    override fun getType(uri: Uri): String {
        val f = file(uri)
        return if (f.extension == "kml") "application/vnd.google-earth.kml+xml" else "application/gpx+xml"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } }.toTypedArray<Any?>()) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Track exports are read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
