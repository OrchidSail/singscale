package dev.singscale.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCodeView(
    content: String,
    qrSize: Dp = 168.dp,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(content) {
        if (content.isBlank()) return@remember null
        runCatching {
            val px = 512
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints)
            val pixels = IntArray(px * px)
            for (y in 0 until px) {
                val offset = y * px
                for (x in 0 until px) {
                    pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.rgb(10, 16, 30) else AndroidColor.WHITE
                }
            }
            Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    } ?: return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "Tailscale 登录二维码",
            modifier = Modifier.size(qrSize)
        )
    }
}
