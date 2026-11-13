"""경로 시간 캐시. 후보→목적지 시간과 위치가 거의 그대로인 차량의 시간을 재사용한다."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable

from ...optimization.types import LatLon
from .base import Matrix, Route, RoutingProvider

# 소수 셋째 자리는 약 100m다. 이 범위 안의 이동은 같은 위치로 본다.
KEY_PRECISION = 3
DEFAULT_TTL_SECONDS = 300


def _key(point: LatLon) -> tuple[float, float]:
    return round(point.lat, KEY_PRECISION), round(point.lon, KEY_PRECISION)


class CachedRouting:
    def __init__(self, inner: RoutingProvider, clock: Callable[[], float] = time.monotonic):
        self._inner = inner
        self._clock = clock
        self._lock = threading.Lock()
        self._pairs: dict[tuple, tuple[float, float | None]] = {}
        self.source = inner.source
        self.traffic_aware = inner.traffic_aware

    def route(self, origin: LatLon, destination: LatLon) -> Route:
        return self._inner.route(origin, destination)

    def matrix(
        self,
        origins: list[LatLon],
        destinations: list[LatLon],
        ttl_seconds: float | None = None,
    ) -> Matrix:
        expires_at = self._clock() + (ttl_seconds or DEFAULT_TTL_SECONDS)
        missing_rows = [row for row, origin in enumerate(origins) if self._row_incomplete(origin, destinations)]
        if missing_rows:
            fetched = self._inner.matrix([origins[row] for row in missing_rows], destinations, ttl_seconds)
            with self._lock:
                for values, row in zip(fetched, missing_rows):
                    for column, value in enumerate(values):
                        self._pairs[(_key(origins[row]), _key(destinations[column]))] = (expires_at, value)
        with self._lock:
            return [
                [self._pairs[(_key(origin), _key(destination))][1] for destination in destinations]
                for origin in origins
            ]

    def _row_incomplete(self, origin: LatLon, destinations: list[LatLon]) -> bool:
        now = self._clock()
        with self._lock:
            for destination in destinations:
                cached = self._pairs.get((_key(origin), _key(destination)))
                if cached is None or cached[0] <= now:
                    return True
        return False
