package net.pocketmap.shared.pmtiles

/** Single, known-length HTTP byte ranges; malformed responses must not enter a cache. */
data class HttpByteRange(val offset: Long, val length: Int) {
    fun total(contentRange: String): Long? {
        if (offset < 0 || length <= 0 || offset > Long.MAX_VALUE - length) return null
        val prefix = "bytes $offset-${offset + length - 1}/"
        if (!contentRange.startsWith(prefix)) return null
        val total = contentRange.removePrefix(prefix)
        if (total.isEmpty() || total.any { it !in '0'..'9' }) return null
        return total.toLongOrNull()?.takeIf { it >= offset + length }
    }
    companion object {
        private val requestPattern = Regex("bytes=(\\d+)-(\\d+)")
        fun matches(requested: String, response: String, bytes: Int): Boolean {
            val match = requestPattern.matchEntire(requested) ?: return false
            val start = match.groupValues[1].toLongOrNull() ?: return false
            val end = match.groupValues[2].toLongOrNull() ?: return false
            if (bytes <= 0 || end < start || end - start != bytes.toLong() - 1) return false
            return HttpByteRange(start, bytes).total(response) != null
        }
    }
}
