package net.pocketmap

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File

/** OkHttp adapter for the existing PMTiles range-cache files. */
class RangeCache(directory: File) {
    private val storage = RangeCacheStorage(directory)
    fun get(chain: Interceptor.Chain, namespace: String): Response? {
        val request = chain.request()
        val entry = storage.get(request.url.toString(), request.header("Range") ?: return null, namespace)
            ?: return null
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(206).message("Partial Content")
            .header("Content-Range", entry.contentRange).header("Accept-Ranges", "bytes")
            .body(entry.data.toResponseBody(entry.mime.toMediaTypeOrNull())).build()
    }
    fun put(url: String, requestedRange: String, namespace: String, contentRange: String, mime: String, data: ByteArray) =
        storage.put(url, requestedRange, namespace, RangeCacheStorage.Entry(contentRange, mime, data))
}
