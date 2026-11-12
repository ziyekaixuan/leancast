package io.leancast.domain.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: media-playback — Requirement: photo casts (still images) are shown
 * instead of being handed to the video player, which cannot render them.
 */
class CastSessionImageTest {

    private class FakePort : PlayerPort {
        var preparedUri: String? = null
        var shownImageUri: String? = null
        var seekCalls = 0
        var speedHistory = mutableListOf<Float>()

        override fun prepareAndPlay(uri: String) {
            preparedUri = uri
        }

        override fun showImage(uri: String) {
            shownImageUri = uri
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) {
            seekCalls++
        }

        override fun stop() = Unit
        override fun currentPositionMs() = 0L
        override fun currentDurationMs() = 0L
        override fun setPlaybackSpeed(speed: Float) {
            speedHistory += speed
        }
    }

    private fun imageDidl(mime: String = "image/jpeg") =
        "<DIDL-Lite><item><dc:title>IMG_1</dc:title>" +
            "<res protocolInfo=\"http-get:*:${mime}:DLNA.ORG_OP=01\">http://192.168.1.5:8080/IMG_1</res>" +
            "</item></DIDL-Lite>"

    private fun videoDidl() =
        "<DIDL-Lite><item><dc:title>Clip</dc:title>" +
            "<res protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01\">http://x/v.mp4</res>" +
            "</item></DIDL-Lite>"

    @Test
    fun `image mime routes play to showImage and reaches PLAYING`() {
        val port = FakePort()
        val session = CastSession(port)
        session.setUri("http://192.168.1.5:8080/IMG_1", imageDidl())
        assertTrue(session.isImage())

        session.play()
        assertEquals("http://192.168.1.5:8080/IMG_1", port.shownImageUri)
        assertNull(port.preparedUri)
        assertEquals(TransportState.TRANSITIONING, session.state)

        session.onPlayerPrepared()
        assertEquals(TransportState.PLAYING, session.state)
    }

    @Test
    fun `image extension is the fallback when no metadata mime exists`() {
        val port = FakePort()
        val session = CastSession(port)
        session.setUri("http://192.168.1.5:8080/photo.JPG", null)
        assertTrue(session.isImage())

        session.play()
        assertEquals("http://192.168.1.5:8080/photo.JPG", port.shownImageUri)
    }

    @Test
    fun `declared video mime wins over an image-looking uri`() {
        val port = FakePort()
        val session = CastSession(port)
        session.setUri("http://x/v.jpg", videoDidl())
        assertFalse(session.isImage())

        session.play()
        assertEquals("http://x/v.jpg", port.preparedUri)
        assertNull(port.shownImageUri)
    }

    @Test
    fun `speed and seek are ignored for images`() {
        val port = FakePort()
        val session = CastSession(port)
        session.setUri("http://192.168.1.5:8080/IMG_1", imageDidl())
        session.play()
        session.onPlayerPrepared()

        assertNull(session.stepPlaybackSpeed(faster = true))
        assertNull(session.cyclePlaybackSpeed())
        assertFalse(session.setPlaybackSpeed(2.0f))
        assertTrue(port.speedHistory.isEmpty())

        session.seekTo(10_000L)
        assertEquals(0, port.seekCalls)
    }

    @Test
    fun `switching from an image to a video clears the image state`() {
        val port = FakePort()
        val session = CastSession(port)
        session.setUri("http://192.168.1.5:8080/IMG_1", imageDidl())
        assertTrue(session.isImage())

        session.setUri("http://x/v.mp4", videoDidl())
        assertFalse(session.isImage())
        session.play()
        assertEquals("http://x/v.mp4", port.preparedUri)
    }
}
