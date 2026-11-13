"""외부 호출 없는 개발·테스트용 추정 경로. 결과에는 항상 ESTIMATE로 표시된다."""

from __future__ import annotations

from ...optimization.geo import haversine_m
from ...optimization.types import LatLon
from .base import Matrix, Route

ROAD_FACTOR = 1.3
SPEED_MPS = 90 / 3.6
POLYLINE_POINTS = 40


class EstimatedRouting:
    source = "ESTIMATE"
    traffic_aware = False

    def route(self, origin: LatLon, destination: LatLon) -> Route:
        polyline = [
            LatLon(
                origin.lat + (destination.lat - origin.lat) * step / POLYLINE_POINTS,
                origin.lon + (destination.lon - origin.lon) * step / POLYLINE_POINTS,
            )
            for step in range(POLYLINE_POINTS + 1)
        ]
        return Route(self._seconds(origin, destination), self._meters(origin, destination), polyline)

    def matrix(
        self,
        origins: list[LatLon],
        destinations: list[LatLon],
        ttl_seconds: float | None = None,
    ) -> Matrix:
        return [[self._seconds(origin, target) for target in destinations] for origin in origins]

    def _meters(self, origin: LatLon, destination: LatLon) -> float:
        return haversine_m(origin, destination) * ROAD_FACTOR

    def _seconds(self, origin: LatLon, destination: LatLon) -> float:
        return self._meters(origin, destination) / SPEED_MPS
