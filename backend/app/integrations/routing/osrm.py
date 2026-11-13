"""OSRM 경로 제공자. 실제 도로망 기준이며 실시간 교통은 반영하지 않는다.

공개 데모 서버는 개발·시연 전용이다. 실제 사용자 위치를 보낼 때는 OSRM_BASE_URL을 자체 서버로 바꾼다.
"""

from __future__ import annotations

import threading
import time

import httpx

from ...optimization.types import LatLon
from .base import Matrix, Route, RoutingError

PUBLIC_DEMO_HOST = "router.project-osrm.org"
# 공개 데모 서버의 이용 정책(초당 1회)을 지킨다.
PUBLIC_DEMO_INTERVAL_SECONDS = 1.0


def _coordinates(points: list[LatLon]) -> str:
    return ";".join(f"{point.lon:.6f},{point.lat:.6f}" for point in points)


class OsrmRouting:
    source = "OSRM"
    traffic_aware = False

    def __init__(
        self,
        base_url: str,
        timeout_seconds: float = 15,
        transport: httpx.BaseTransport | None = None,
    ):
        self._client = httpx.Client(base_url=base_url, timeout=timeout_seconds, transport=transport)
        self._interval = PUBLIC_DEMO_INTERVAL_SECONDS if PUBLIC_DEMO_HOST in base_url else 0.0
        self._lock = threading.Lock()
        self._last_request_at = 0.0

    def route(self, origin: LatLon, destination: LatLon) -> Route:
        body = self._get(
            f"/route/v1/driving/{_coordinates([origin, destination])}",
            {"overview": "full", "geometries": "geojson"},
        )
        routes = body.get("routes") or []
        if not routes:
            raise RoutingError("경로를 찾지 못했습니다.")
        route = routes[0]
        polyline = [LatLon(lat, lon) for lon, lat in route["geometry"]["coordinates"]]
        return Route(route["duration"], route["distance"], polyline)

    def matrix(
        self,
        origins: list[LatLon],
        destinations: list[LatLon],
        ttl_seconds: float | None = None,
    ) -> Matrix:
        sources = ";".join(str(index) for index in range(len(origins)))
        targets = ";".join(str(len(origins) + index) for index in range(len(destinations)))
        body = self._get(
            f"/table/v1/driving/{_coordinates([*origins, *destinations])}",
            {"sources": sources, "destinations": targets, "annotations": "duration"},
        )
        durations = body.get("durations")
        if durations is None:
            raise RoutingError("경로 시간 행렬이 없습니다.")
        return durations

    def _get(self, path: str, params: dict[str, str]) -> dict:
        self._wait_for_slot()
        try:
            response = self._client.get(path, params=params)
            response.raise_for_status()
            body = response.json()
        except (httpx.HTTPError, ValueError) as error:
            raise RoutingError(f"경로 서버 호출에 실패했습니다: {error}") from error
        if body.get("code") != "Ok":
            raise RoutingError(f"경로 서버 오류: {body.get('code')}")
        return body

    def _wait_for_slot(self) -> None:
        with self._lock:
            delay = self._last_request_at + self._interval - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            self._last_request_at = time.monotonic()
