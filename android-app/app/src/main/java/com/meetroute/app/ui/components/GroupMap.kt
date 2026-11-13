package com.meetroute.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetroute.app.domain.GroupState
import com.meetroute.app.ui.theme.Accent
import com.meetroute.app.ui.theme.Caution
import com.meetroute.app.ui.theme.Positive
import com.meetroute.app.ui.theme.Surface2
import com.meetroute.app.ui.theme.TextMuted
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private data class MapPoint(val lat: Double, val lon: Double, val label: String, val color: Color, val square: Boolean = false)

private const val MAP_MARGIN = 70f
private const val MIN_SPAN_DEGREES = 0.02

/**
 * 그룹 위치 개요도. 지도 타일 없이 차량·합류 지점·목적지의 상대 위치만 그린다.
 * 기본 내비 화면 위에 마커를 올리는 기능은 지원 여부가 확인되지 않아 별도 화면으로 둔다(기획서 F02).
 */
@Composable
fun GroupMap(group: GroupState, myVehicleId: String?, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val points = buildList {
        group.participants.forEach { vehicle ->
            val lat = vehicle.lat
            val lon = vehicle.lon
            if (lat != null && lon != null) {
                val color = if (vehicle.vehicleId == myVehicleId) Accent else Positive
                add(MapPoint(lat, lon, vehicle.nickname, color))
            }
        }
        val point = group.meetingPoint
        val pointLat = point?.latitude
        val pointLon = point?.longitude
        if (point != null && pointLat != null && pointLon != null) {
            add(MapPoint(pointLat, pointLon, point.name, Caution, square = true))
        }
        // 비공개 드라이브의 그룹원에게는 목적지가 없으므로 그리지 않는다.
        group.destination?.let { add(MapPoint(it.lat, it.lon, it.name, TextMuted, square = true)) }
    }
    Box(modifier = modifier.background(Surface2, RoundedCornerShape(16.dp))) {
        if (points.isEmpty()) {
            Text(
                "위치를 공유하면 차량이 표시됩니다",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) { drawPoints(points, textMeasurer) }
        }
    }
}

private fun DrawScope.drawPoints(points: List<MapPoint>, textMeasurer: TextMeasurer) {
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val aspect = cos(Math.toRadians((minLat + maxLat) / 2))
    val spanLat = max(maxLat - minLat, MIN_SPAN_DEGREES)
    val spanLon = max((maxLon - minLon) * aspect, MIN_SPAN_DEGREES)
    val usableWidth = size.width - MAP_MARGIN * 2
    val usableHeight = size.height - MAP_MARGIN * 2
    val scale = min(usableWidth / spanLon, usableHeight / spanLat)
    val centerX = size.width / 2
    val centerY = size.height / 2
    val midLat = (minLat + maxLat) / 2
    val midLon = (minLon + maxLon) / 2

    fun project(point: MapPoint) = Offset(
        (centerX + (point.lon - midLon) * aspect * scale).toFloat(),
        (centerY - (point.lat - midLat) * scale).toFloat(),
    )

    val target = points.firstOrNull { it.square }?.let(::project)
    points.filterNot { it.square }.forEach { vehicle ->
        if (target != null) {
            drawLine(
                color = vehicle.color.copy(alpha = 0.5f),
                start = project(vehicle),
                end = target,
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
            )
        }
    }
    points.forEach { point ->
        val center = project(point)
        if (point.square) {
            drawRect(
                color = point.color,
                topLeft = center - Offset(12f, 12f),
                size = Size(24f, 24f),
                style = Stroke(width = 5f),
            )
        } else {
            drawCircle(color = point.color, radius = 13f, center = center)
        }
        drawText(
            textMeasurer = textMeasurer,
            text = point.label,
            topLeft = center + Offset(18f, -14f),
            style = TextStyle(color = Color.White, fontSize = 15.sp),
        )
    }
}
