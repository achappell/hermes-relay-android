package com.achappell.hermesrelay

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class HomePairingQrDecoderTest {

    @Test
    fun a_padded_camera_frame_of_a_pairing_qr_decodes_to_an_accepted_link() {
        val link = "hermes-home://pair?home=https%3A%2F%2Fhome.example.ts.net&code=K7Q4MX"
        val frame = cameraFrame(link, size = 480, rowPadding = 32)

        val decoded = HomePairingQrDecoder.decode(
            HomePairingQrDecoder.reader(),
            HomePairingQrDecoder.visibleLuminance(frame.plane, frame.rowStride, 480, 480),
            480,
            480,
        )

        assertEquals(link, decoded)
        assertEquals(HomePairingScan.Result.Accepted(link), HomePairingScan.evaluate(listOf(decoded)))
    }

    @Test
    fun another_qr_code_is_decoded_but_not_accepted() {
        val frame = cameraFrame("https://example.com/menu", size = 360, rowPadding = 0)
        val decoded = HomePairingQrDecoder.decode(
            HomePairingQrDecoder.reader(),
            HomePairingQrDecoder.visibleLuminance(frame.plane, frame.rowStride, 360, 360),
            360,
            360,
        )
        assertEquals(HomePairingScan.Result.NotPairingCode, HomePairingScan.evaluate(listOf(decoded)))
    }

    @Test
    fun a_frame_without_a_code_decodes_to_nothing() {
        val blank = ByteArray(200 * 200) { 0x7f }
        assertNull(HomePairingQrDecoder.decode(HomePairingQrDecoder.reader(), blank, 200, 200))
    }

    private class Frame(val plane: ByteBuffer, val rowStride: Int)

    /** Renders [text] as a Y plane with row padding, like a camera frame. */
    private fun cameraFrame(text: String, size: Int, rowPadding: Int): Frame {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val stride = size + rowPadding
        val bytes = ByteArray(stride * size) { 0x55 }
        for (y in 0 until size) {
            for (x in 0 until size) {
                bytes[y * stride + x] = if (matrix[x, y]) 0x10 else 0xEB.toByte()
            }
        }
        return Frame(ByteBuffer.wrap(bytes), stride)
    }
}
