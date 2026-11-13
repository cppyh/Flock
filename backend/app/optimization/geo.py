"""좌표 계산. 후보를 고르는 저비용 단계에만 쓰며 주행 시간은 경로 제공자에서 받는다."""

from __future__ import annotations

import math
from dataclasses import dataclass

from .types import LatLon

EARTH_RADIUS_M = 6_371_000
METERS_PER_DEGREE = math.pi * EARTH_RADIUS_M / 180
# 긴 경로를 이 구간 수로 묶어 먼 묶음은 통째로 건너뛴다.
CHUNK_SEGMENTS = 32


def haversine_m(a: LatLon, b: LatLon) -> float:
    lat1, lat2 = math.radians(a.lat), math.radians(b.lat)
    d_lat = lat2 - lat1
    d_lon = math.radians(b.lon - a.lon)
    h = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def bearing_deg(a: LatLon, b: LatLon) -> float:
    lat1, lat2 = math.radians(a.lat), math.radians(b.lat)
    d_lon = math.radians(b.lon - a.lon)
    y = math.sin(d_lon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(d_lon)
    return math.degrees(math.atan2(y, x)) % 360


def angle_difference_deg(a: float, b: float) -> float:
    difference = abs(a - b) % 360
    return min(difference, 360 - difference)


def midpoint(points: list[LatLon]) -> LatLon:
    return LatLon(sum(p.lat for p in points) / len(points), sum(p.lon for p in points) / len(points))


@dataclass(frozen=True)
class RouteLocation:
    """경로 위에서 어떤 지점과 가장 가까운 위치."""

    distance_m: float
    along_m: float
    heading_deg: float
    fraction: float


@dataclass(frozen=True)
class _Chunk:
    """연속한 구간 묶음과 그 경계 상자."""

    first_segment: int
    last_segment: int
    min_lat: float
    max_lat: float
    min_lon: float
    max_lon: float

    def distance_floor_m(self, point: LatLon, scale: float) -> float:
        """이 묶음의 어떤 구간도 이 값보다 point에 가까울 수 없다. _segment_distance와 같은 평면을 쓴다."""
        d_lat = max(self.min_lat - point.lat, 0.0, point.lat - self.max_lat)
        d_lon = max(self.min_lon - point.lon, 0.0, point.lon - self.max_lon)
        return math.hypot(d_lon * scale, d_lat) * METERS_PER_DEGREE


class RouteLine:
    """폴리라인 위 최근접 위치와 그 지점의 진행 방위각을 구한다."""

    def __init__(self, polyline: list[LatLon], heading_span_m: float = 1500):
        if len(polyline) < 2:
            raise ValueError("경로 형상에는 두 개 이상의 점이 필요합니다.")
        self._points = polyline
        self._heading_span_m = heading_span_m
        self._cumulative = [0.0]
        for start, end in zip(polyline, polyline[1:]):
            self._cumulative.append(self._cumulative[-1] + haversine_m(start, end))
        self.length_m = self._cumulative[-1]
        self._chunks = _build_chunks(polyline)

    def locate(self, point: LatLon) -> RouteLocation:
        best_distance = math.inf
        best_along = 0.0
        scale = math.cos(math.radians(point.lat))
        # 가까운 묶음부터 보면 나머지는 대부분 경계 상자 비교만으로 건너뛴다.
        floors = sorted((chunk.distance_floor_m(point, scale), chunk.first_segment, chunk) for chunk in self._chunks)
        for floor, _, chunk in floors:
            if floor >= best_distance:
                break
            for index in range(chunk.first_segment, chunk.last_segment):
                distance, ratio = _segment_distance(point, self._points[index], self._points[index + 1], scale)
                if distance < best_distance:
                    best_distance = distance
                    segment = self._cumulative[index + 1] - self._cumulative[index]
                    best_along = self._cumulative[index] + ratio * segment
        return RouteLocation(
            distance_m=best_distance,
            along_m=best_along,
            heading_deg=self._heading_at(best_along),
            fraction=best_along / self.length_m if self.length_m else 0.0,
        )

    def _heading_at(self, along_m: float) -> float:
        before = self._point_at(max(0.0, along_m - self._heading_span_m))
        after = self._point_at(min(self.length_m, along_m + self._heading_span_m))
        return bearing_deg(before, after)

    def _point_at(self, along_m: float) -> LatLon:
        for index in range(1, len(self._cumulative)):
            if along_m <= self._cumulative[index]:
                start, end = self._points[index - 1], self._points[index]
                segment = self._cumulative[index] - self._cumulative[index - 1]
                ratio = (along_m - self._cumulative[index - 1]) / segment if segment else 0.0
                return LatLon(
                    start.lat + (end.lat - start.lat) * ratio,
                    start.lon + (end.lon - start.lon) * ratio,
                )
        return self._points[-1]


def _build_chunks(polyline: list[LatLon]) -> list[_Chunk]:
    chunks = []
    segments = len(polyline) - 1
    for first in range(0, segments, CHUNK_SEGMENTS):
        last = min(first + CHUNK_SEGMENTS, segments)
        points = polyline[first : last + 1]
        chunks.append(
            _Chunk(
                first,
                last,
                min(p.lat for p in points),
                max(p.lat for p in points),
                min(p.lon for p in points),
                max(p.lon for p in points),
            )
        )
    return chunks


def _segment_distance(point: LatLon, start: LatLon, end: LatLon, scale: float) -> tuple[float, float]:
    """작은 구간을 평면으로 보고 점과 선분의 거리(m)와 선분 위 비율을 구한다."""
    ax = (start.lon - point.lon) * scale * METERS_PER_DEGREE
    ay = (start.lat - point.lat) * METERS_PER_DEGREE
    bx = (end.lon - point.lon) * scale * METERS_PER_DEGREE
    by = (end.lat - point.lat) * METERS_PER_DEGREE
    dx, dy = bx - ax, by - ay
    length_squared = dx * dx + dy * dy
    ratio = 0.0 if length_squared == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / length_squared))
    return math.hypot(ax + ratio * dx, ay + ratio * dy), ratio
