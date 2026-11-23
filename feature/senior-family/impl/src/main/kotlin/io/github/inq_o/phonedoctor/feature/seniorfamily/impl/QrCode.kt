package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 가장자리 여백(4칸)을 포함한 QR 칸 배열 */
internal fun qrMatrix(payload: String): BitMatrix = QRCodeWriter().encode(
    payload,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
)

/** 다크 모드에서도 흰 바탕에 검은 칸으로 그린다 (반전된 QR은 못 읽는 스캐너가 있음) */
@Composable
internal fun QrCode(payload: String, description: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(payload) { qrMatrix(payload) }
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        drawRect(Color.White)
        val cell = this.size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                // 칸 사이 틈이 생기지 않게 살짝 겹쳐 그린다
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
