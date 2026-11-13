"""경로 제공자 인터페이스와 호출량 계측."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from ...optimization.types import ApiUsage, LatLon

Matrix = list[list[float | None]]


class RoutingError(Exception):
    """경로 조회 실패. 호출부는 이 경우 추정값을 만들지 않고 마지막 정상 결과를 보여 준다."""


@dataclass(frozen=True)
class Route:
    duration_seconds: float
    distance_m: float
    polyline: list[LatLon]


class RoutingProvider(Protocol):
    source: str
    traffic_aware: bool

    def route(self, origin: LatLon, destination: LatLon) -> Route:
        """기본 경로의 시간·거리·형상을 돌려준다."""
        ...

    def matrix(
        self,
        origins: list[LatLon],
        destinations: list[LatLon],
        ttl_seconds: float | None = None,
    ) -> Matrix:
        """origins × destinations 주행 시간(초). 경로가 없는 칸은 None이다."""
        ...


class MeteredRouting:
    """design.md §8.5의 호출량 추적. 추천 한 번마다 새로 감싸서 쓴다."""

    def __init__(self, inner: RoutingProvider):
        self._inner = inner
        self.source = inner.source
        self.traffic_aware = inner.traffic_aware
        self.usage = ApiUsage()

    def route(self, origin: LatLon, destination: LatLon) -> Route:
        self.usage.route_calls += 1
        return self._inner.route(origin, destination)

    def matrix(
        self,
        origins: list[LatLon],
        destinations: list[LatLon],
        ttl_seconds: float | None = None,
    ) -> Matrix:
        self.usage.matrix_calls += 1
        self.usage.matrix_elements += len(origins) * len(destinations)
        return self._inner.matrix(origins, destinations, ttl_seconds)
