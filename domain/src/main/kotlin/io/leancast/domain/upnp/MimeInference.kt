package io.leancast.domain.upnp

/**
 * Media type inference for cast URLs. Deliberately fuzzy: CN senders hide
 * HLS manifests mid-path or in query strings (e.g. youku `/playlist/m3u8?vid=`),
 * so "m3u8" anywhere in the URL selects the HLS MIME type. Returns null for
 * progressive media so the player sniffs the container itself.
 */
object MimeInference {

    const val HLS: String = "application/vnd.apple.mpegurl"
    const val DASH: String = "application/dash+xml"

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun guessMimeType(uri: String?): String? {
        if (uri == null) return null
        return when {
            uri.contains(".m3u8", ignoreCase = true) ||
                uri.contains("m3u8", ignoreCase = true) -> HLS
            uri.contains(".mpd", ignoreCase = true) -> DASH
            else -> null
        }
    }

    /**
     * True when the URI's path (query and fragment stripped) ends in a
     * still-image extension the platform decoder can handle. HEIC/HEIF are
     * deliberately absent: BitmapFactory cannot decode them before API 28.
     */
    fun isImageUri(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val path = uri.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }
}
