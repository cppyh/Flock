package com.meetroute.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF4FC3F7)
val Positive = Color(0xFF66BB6A)
val Caution = Color(0xFFFFB74D)
val Danger = Color(0xFFEF5350)
val Surface1 = Color(0xFF1B2128)
val Surface2 = Color(0xFF262E37)
val TextMuted = Color(0xFFA9B4BF)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00222F),
    background = Color(0xFF11161B),
    onBackground = Color(0xFFF2F5F7),
    surface = Surface1,
    onSurface = Color(0xFFF2F5F7),
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    error = Danger,
)

// 차량 화면은 팔 길이만큼 떨어져서 보므로 일반 앱보다 글자를 크게 둔다.
private val CarTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp),
    bodyMedium = TextStyle(fontSize = 18.sp),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun MeetRouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = CarTypography, content = content)
}
