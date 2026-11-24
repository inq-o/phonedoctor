package io.github.inq_o.phonedoctor.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 이 글꼴 배율부터 버튼 쌍을 세로로 쌓는다. 22sp 버튼 글자가 반쪽 폭(약 150dp)에 한 줄로 들어가지 않기 시작하는 크기 */
const val PAIR_STACK_FONT_SCALE = 1.3f

/**
 * 버튼 쌍 (ux-flows "버튼 쌍"). 왼쪽 [first]는 물러나기, 오른쪽 [second]는 주요 행동.
 * 시스템 삭제 창 [취소 | 확인]과 같은 순서다. 글꼴이 커서 한 줄에 안 들어가면 세로로 쌓고 주요 버튼을 아래에 둔다.
 * 각 버튼은 받은 Modifier를 써야 같은 높이·폭이 된다.
 */
@Composable
fun PdButtonPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalDensity.current.fontScale >= PAIR_STACK_FONT_SCALE) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            first(Modifier.heightIn(min = 64.dp))
            second(Modifier.heightIn(min = 64.dp))
        }
    } else {
        Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            first(Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp))
            second(Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp))
        }
    }
}

/**
 * 홈 메뉴 칸 (ux-flows S1·S1′). 아이콘과 글자를 함께, 최소 104dp. [filled]는 화면의 주요 행동 하나에만.
 * 상태가 바뀌어도 칸의 자리와 이름은 그대로 두고 채움만 바꾼다 (원칙 2)
 */
@Composable
fun PdMenuTile(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = if (filled) null else BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        colors = if (filled) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onBackground,
            )
        },
        contentPadding = PaddingValues(12.dp),
        modifier = modifier.heightIn(min = 104.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/** 메뉴 칸 2열 격자. 한 줄의 칸은 같은 높이다 */
@Composable
fun PdMenuGrid(
    rows: List<List<@Composable (Modifier) -> Unit>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}
