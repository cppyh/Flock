package com.meetroute.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meetroute.app.domain.ParticipantStatus
import com.meetroute.app.ui.theme.Caution
import com.meetroute.app.ui.theme.Danger
import com.meetroute.app.ui.theme.Positive
import com.meetroute.app.ui.theme.TextMuted

private val TOUCH_TARGET = 76.dp

/**
 * 나란히 두면 글자가 밀려나는 너비의 경계.
 * Pleos 에뮬레이터의 앱 창은 기본 1682dp, 왼쪽 패널에 놓이면 853dp다. 창 크기는 운전자가 바꿀 수 있다.
 */
val WIDE_LAYOUT_MIN_WIDTH = 900.dp

/** 넓으면 두 칸으로 나누고 좁으면 한 칸으로 이어 붙인다. */
@Composable
fun AdaptivePanes(
    modifier: Modifier = Modifier,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    val spacing = Arrangement.spacedBy(16.dp)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val pane = Modifier.weight(1f).fillMaxHeight()
                Column(pane.verticalScroll(rememberScrollState()), spacing, content = first)
                Column(pane.verticalScroll(rememberScrollState()), spacing, content = second)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), spacing) {
                first()
                second()
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text) }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Danger),
        modifier = modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text) }
}

@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

@Composable
fun Banner(text: String, color: Color = Caution, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Chip(text: String, color: Color = TextMuted) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatusChip(status: ParticipantStatus) {
    val color = when (status) {
        ParticipantStatus.ACTIVE -> Positive
        ParticipantStatus.STALE -> Caution
        ParticipantStatus.PAUSED, ParticipantStatus.NO_LOCATION, ParticipantStatus.LEFT -> TextMuted
    }
    Chip(status.label, color)
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
