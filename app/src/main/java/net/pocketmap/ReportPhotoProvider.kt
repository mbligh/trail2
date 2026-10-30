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

/** URI grants expose only an explicitly created report photo to the camera or share target. */
class ReportPhotoProvider : ContentProvider() {
    companion object {
        fun photoFile(filesDir: File, name: String): File {
            if (!name.matches(Regex("Trail-report-[A-Za-z0-9-]+\\.jpg"))) throw FileNotFoundException()
            val directory = File(filesDir, "report-photos").canonicalFile
            val file = File(directory, name).canonicalFile
            if (file.parentFile != directory || !file.isFile) throw FileNotFoundException()
            return file
        }
    }
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        if (uri.pathSegments.size != 2 || uri.pathSegments[0] != "photo") throw FileNotFoundException()
        return photoFile(context!!.filesDir, uri.pathSegments[1])
    }
    override fun getType(uri: Uri): String { file(uri); return "image/jpeg" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } }.toTypedArray<Any?>()) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("Unsupported mode")
        }
        return ParcelFileDescriptor.open(file(uri), flags)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
