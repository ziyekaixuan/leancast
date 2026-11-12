package io.leancast.domain.cast

import io.leancast.domain.upnp.MimeInference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: media-playback — Compatible media fetching (real-world senders put
 * "m3u8" mid-path or in query, e.g. youku /playlist/m3u8?vid=...).
 */
class MimeInferenceTest {

    @Test
    fun `hls by classic extension`() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            MimeInference.guessMimeType("https://upos-sz-mirrorbilivideo1.bilivideo.com/x.m3u8?sign=1"),
        )
    }

    @Test
    fun `hls when m3u8 appears mid-path or in query`() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            MimeInference.guessMimeType("http://pl-ali.youku.com/playlist/m3u8?vid=XNjA5&dlnaopt=2"),
        )
        assertEquals(
            "application/vnd.apple.mpegurl",
            MimeInference.guessMimeType("http://cdn.x/get?type=m3u8&qn=80"),
        )
    }

    @Test
    fun `dash by extension`() {
        assertEquals(
            "application/dash+xml",
            MimeInference.guessMimeType("https://x.example/v.mpd?token=t"),
        )
    }

    @Test
    fun `plain progressive returns null`() {
        assertEquals(null, MimeInference.guessMimeType("http://192.168.31.15:8000/e2e.mp4"))
        assertEquals(null, MimeInference.guessMimeType("http://x.example/v.flv"))
        assertEquals(null, MimeInference.guessMimeType("https://x.example/stream"))
    }

    @Test
    fun `image by extension, case-insensitive, query and fragment ignored`() {
        assertTrue(MimeInference.isImageUri("http://192.168.1.5:8080/IMG_1234.JPG"))
        assertTrue(MimeInference.isImageUri("http://192.168.1.5:8080/photo.jpeg?size=large"))
        assertTrue(MimeInference.isImageUri("http://192.168.1.5:8080/pic.png#frag"))
        assertTrue(MimeInference.isImageUri("http://192.168.1.5:8080/a.webp"))
        assertTrue(MimeInference.isImageUri("http://192.168.1.5:8080/a.gif"))
        assertTrue(MimeInference.isImageUri("http://192.168.1.5:8080/a.bmp"))
    }

    @Test
    fun `non-image uris are not images`() {
        assertFalse(MimeInference.isImageUri("http://192.168.1.5:8080/video.mp4"))
        assertFalse(MimeInference.isImageUri("http://192.168.1.5:8080/photo"))
        assertFalse(MimeInference.isImageUri("http://192.168.1.5:8080/a.jpg/123"))
        assertFalse(MimeInference.isImageUri("http://192.168.1.5:8080/a.heic"))
        assertFalse(MimeInference.isImageUri(null))
        assertFalse(MimeInference.isImageUri(""))
    }
}
