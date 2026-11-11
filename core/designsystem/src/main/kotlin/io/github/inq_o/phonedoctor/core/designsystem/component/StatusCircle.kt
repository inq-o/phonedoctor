package io.github.inq_o.phonedoctor.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.inq_o.phonedoctor.core.designsystem.theme.LocalStatusColors

enum class StatusKind { OK, ATTENTION }

/**
 * 홈 맨 위의 큰 상태 원. 색만으로 구분하지 않고 아이콘을 같이 쓴다.
 * 뜻은 바로 아래 상태 문장이 말하므로 화면 낭독에서는 건너뛴다.
 */
@Composable
fun StatusCircle(kind: StatusKind, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    Box(
        modifier = modifier.size(120.dp).clip(CircleShape)
            .background(if (kind == StatusKind.OK) colors.safe else colors.caution)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (kind == StatusKind.OK) Icons.Filled.Check else Icons.Filled.Warning,
            contentDescription = null,
            tint = colors.onStatus,
            modifier = Modifier.size(64.dp),
        )
    }
}
