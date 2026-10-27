package net.pocketmap

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Persist PMTiles HTTP ranges as well as their directory/header requests. */
class RangeCache(private val directory: File) {
    private var writes = 0
    init { directory.mkdirs() }
    @Synchronized fun get(chain: Interceptor.Chain, namespace: String): Response? {
        val request = chain.request()
        val file = file(request.url.toString(), request.header("Range") ?: return null, namespace)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val range = input.readUTF(); val mime = input.readUTF(); val length = input.readInt()
                require(length in 1..32*1024*1024)
                val data = ByteArray(length); input.readFully(data)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                    .header("Content-Range", range).header("Accept-Ranges", "bytes")
                    .body(data.toResponseBody(mime.toMediaType())).build()
            }
        }.getOrElse { file.delete(); null }
    }
    @Synchronized fun put(url: String, requestedRange: String, namespace: String, contentRange: String, mime: String, data: ByteArray) {
        if (data.isEmpty() || data.size > 32*1024*1024 || contentRange.isBlank()) return
        val file = file(url, requestedRange, namespace)
        val temporary = File(file.path + ".tmp")
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeUTF(contentRange); output.writeUTF(mime); output.writeInt(data.size); output.write(data)
            }
            check(temporary.renameTo(file))
        } finally { temporary.delete() }
        if (++writes % 32 == 0) {
            val files = directory.listFiles()?.filter { it.extension == "range" }?.sortedBy { it.lastModified() } ?: return
            var size = files.sumOf { it.length() }
            if (size > 500L*1024*1024) for (victim in files) {
                val length = victim.length()
                if (victim.delete()) size -= length
                if (size <= 400L*1024*1024) break
            }
        }
    }
    private fun file(url: String, range: String, namespace: String) = File(directory, TileRepository.hash("$url|$range|$namespace") + ".range")
}
