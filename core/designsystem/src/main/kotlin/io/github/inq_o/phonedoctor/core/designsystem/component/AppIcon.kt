package io.github.inq_o.phonedoctor.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** 설치된 앱의 아이콘. 앱이 이미 지워졌으면 빈 칸으로 둔다. 이름이 옆에 있으므로 화면 낭독 라벨은 없다 */
@Composable
fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(size).clip(RoundedCornerShape(size / 5)))
    } else {
        Box(modifier.size(size).clip(RoundedCornerShape(size / 5)).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
