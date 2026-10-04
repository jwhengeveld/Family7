package com.xiappdesign.family7.mobile.cast

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Een Cast-ontvanger moet weten wat voor stream hij krijgt. Streampartner zet
 * een mp4-bestand achter een HLS-playlist, dus alleen het einde van het pad telt.
 */
class MimeTypeTest {

    @Test
    fun `on demand via Wowza is HLS ondanks mp4 in het pad`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            Family7MediaItemConverter.guessMimeType(
                "https://highvolume08.streampartner.nl/ondfam7/_definst_/video/Afl02.mp4/playlist.m3u8?token=x"
            )
        )
    }

    @Test
    fun `live smil-playlist is HLS`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            Family7MediaItemConverter.guessMimeType(
                "https://highvolume155.streampartner.nl/family7_teracue/smil:livestream.smil/playlist.m3u8?t=1"
            )
        )
    }

    @Test
    fun `een los mp4-bestand is mp4, ook met een token erachter`() {
        assertEquals(
            MimeTypes.VIDEO_MP4,
            Family7MediaItemConverter.guessMimeType("https://cdn.example.nl/oud/aflevering.MP4?token=abc")
        )
    }
}
