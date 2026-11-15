package com.yokhan.gates.shared.ui.access

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal data class YokhanQrMatrix(
    val size: Int,
    val modules: BooleanArray,
)

internal fun encodeYokhanQrMatrix(content: String): YokhanQrMatrix? {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(trimmed, BarcodeFormat.QR_CODE, 0, 0, hints)
        val dim = matrix.width.coerceAtLeast(matrix.height)
        val bits = BooleanArray(dim * dim)
        for (y in 0 until dim) {
            for (x in 0 until dim) {
                bits[y * dim + x] = x < matrix.width && y < matrix.height && matrix.get(x, y)
            }
        }
        YokhanQrMatrix(size = dim, modules = bits)
    }.getOrNull()
}

@Composable
fun YokhanQrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 172.dp,
) {
    val qr = remember(content) { encodeYokhanQrMatrix(content) } ?: return
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(10.dp)
            .testTag("form.auth.qr"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size - 20.dp)) {
            val moduleSize = this.size.minDimension / qr.size.toFloat()
            for (y in 0 until qr.size) {
                for (x in 0 until qr.size) {
                    if (qr.modules[y * qr.size + x]) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * moduleSize, y * moduleSize),
                            size = Size(moduleSize + 0.5f, moduleSize + 0.5f),
                        )
                    }
                }
            }
        }
    }
}
