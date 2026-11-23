package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QrCodeTest {
    @Test
    fun drawnQrReadsBackAsPayloadWithQuietZone() {
        val payload = "phonedoctor://pair?c=482913"
        val matrix = qrMatrix(payload)
        // 가장자리 4칸은 비어 있어야 스캐너가 찾는다
        for (i in 0 until matrix.width) {
            for (edge in 0 until 4) {
                assertFalse(matrix[i, edge])
                assertFalse(matrix[edge, i])
            }
        }
        val scale = 4
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { i ->
            if (matrix[(i % size) / scale, (i / size) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        assertEquals(payload, QRCodeReader().decode(bitmap).text)
    }
}
