package io.github.inq_o.phonedoctor.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.inq_o.phonedoctor.core.designsystem.theme.BadgeColor
import io.github.inq_o.phonedoctor.core.designsystem.theme.GuardianTheme

/**
 * 보호자 화면 틀 (ux-flows.md §1 "보호자 화면", §5 보호자 열). 흔한 앱 모양: 위 막대(뒤로 ← · 제목 · 오른쪽 위 동작),
 * 스크롤되는 본문, 아래에 붙는 버튼 영역. 좌우 16dp, 버튼 48dp·간격 8dp.
 *
 * @param title 위 막대 제목. 홈은 [large] (22sp), 다른 화면은 18sp
 * @param onBack 있으면 왼쪽 위 뒤로(←). 아래쪽 [돌아가기] 버튼은 두지 않는다
 * @param topActions 오른쪽 위 아이콘 버튼 (홈의 설정 톱니)
 * @param contentPadding 본문 좌우 여백. 흰 목록 띠([GuardianBand])를 끝까지 펴려면 0
 * @param bottomBar 화면 아래에 붙는 영역 (같이 보낼 메시지 + 버튼 쌍 등). 흰 면 + 위 구분선
 */
@Composable
fun GuardianScreen(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    large: Boolean = false,
    topActions: @Composable RowScope.() -> Unit = {},
    contentPadding: Dp = 16.dp,
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GuardianTheme {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            if (title != null || onBack != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (title != null) {
                        Text(
                            text = title,
                            style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    topActions()
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = contentPadding, vertical = 8.dp),
                content = content,
            )
            if (bottomBar != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Column(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = bottomBar,
                )
            }
            // 줄 버튼이 없으면 빈 여백도 두지 않는다
            if (actions != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        }
    }
}

/** 묶음 제목 ("답변할 요청", "알림"). 14sp, 보조색 */
@Composable
fun GuardianSectionHeader(text: String, modifier: Modifier = Modifier, topPadding: Dp = 0.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * 흰 목록 띠: 위아래 구분선, 좌우 끝까지 (같은 테두리 카드를 늘어놓지 않는다, ux-flows §1 보호자 화면).
 * 안의 줄 사이는 [GuardianRowDivider]
 */
@Composable
fun GuardianBand(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), content = content)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

/** 띠 안 줄 사이 구분선 (왼쪽 16dp 들여씀) */
@Composable
fun GuardianRowDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
}

/**
 * 띠 안의 한 줄. [onClick]이 있으면 줄 전체가 눌리고 오른쪽에 › ([chevron]이 false면 생략, 예: + 아이콘이 있는 줄).
 * @param leading 앱 아이콘·상태 원
 * @param trailing 배지·스위치 (› 앞)
 */
@Composable
fun GuardianRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    chevron: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = if (subtitle != null) 72.dp else 56.dp)
            .let { if (onClick != null) it.clickable(role = role, onClick = onClick) else it }
            .padding(start = 16.dp, end = if (onClick != null) 8.dp else 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                color = titleColor,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
        if (onClick != null && chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 상태 배지 (ux-flows §5 배지 토큰). 색만으로 구분하지 않게 늘 글자를 넣는다 */
@Composable
fun GuardianBadge(text: String, color: BadgeColor, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(color.background).padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color.ink, maxLines = 1)
    }
}

/** 보호자 화면의 주요 버튼 (48dp). [danger]는 확인창 밖에서 쓰지 않는다 (빨간 채움은 시니어 [지우기]에만, ux-flows §1) */
@Composable
fun GuardianPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (danger) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        },
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 보호자 화면의 테두리 버튼 (48dp) */
@Composable
fun GuardianOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 목록 띠 사이 여백 */
@Composable
fun GuardianGap(height: Dp = 24.dp) = Spacer(Modifier.height(height))
