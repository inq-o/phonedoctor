package io.github.inq_o.phonedoctor.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 시니어 화면 틀. 읽는 내용은 위, 누르는 버튼은 아래에 고정한다 (ux-flows.md 원칙 8).
 * 글꼴을 크게 해도 잘리지 않게 위쪽 내용은 스크롤된다.
 *
 * [footer]는 홈 메뉴 칸처럼 아래쪽에 두지만 고정하지 않는 영역이다. 자리가 남으면 바닥에 붙고,
 * 글꼴을 키워 한 화면에 안 들어가면 위 내용 아래에 이어 붙어 같이 스크롤된다 (위 내용을 가리지 않음).
 */
@Composable
fun SeniorScreen(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        topBar()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
                if (footer != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = footer,
                    )
                }
            }
        }
        if (actions != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = actions,
            )
        }
    }
}
