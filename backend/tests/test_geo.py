"""경로 위 최근접 위치 계산. 묶음 건너뛰기가 전체 탐색과 같은 답을 내는지 확인한다."""

from __future__ import annotations

import math
import random

import pytest

from app.optimization.geo import RouteLine, _segment_distance
from app.optimization.types import LatLon


def winding_route(points: int, seed: int) -> list[LatLon]:
    rng = random.Random(seed)
    lat, lon = 37.5, 127.0
    route = []
    for _ in range(points):
        route.append(LatLon(lat, lon))
        lat += rng.uniform(-0.002, 0.004)
        lon += rng.uniform(-0.001, 0.006)
    return route


def full_scan_distance(route: list[LatLon], point: LatLon) -> float:
    scale = math.cos(math.radians(point.lat))
    return min(_segment_distance(point, a, b, scale)[0] for a, b in zip(route, route[1:]))


@pytest.mark.parametrize("points", [2, 3, 33, 34, 65, 1000])
def test_locate_matches_full_scan(points):
    route = winding_route(points, seed=points)
    line = RouteLine(route)
    rng = random.Random(1)
    for _ in range(100):
        anchor = rng.choice(route)
        point = LatLon(anchor.lat + rng.uniform(-0.5, 0.5), anchor.lon + rng.uniform(-0.5, 0.5))
        assert line.locate(point).distance_m == pytest.approx(full_scan_distance(route, point), abs=1e-6)


def test_location_on_route_has_zero_distance_and_matching_fraction():
    route = [LatLon(37.5, 127.0 + step * 0.01) for step in range(101)]
    located = RouteLine(route).locate(LatLon(37.5, 127.5))
    assert located.distance_m == pytest.approx(0, abs=1e-6)
    assert located.fraction == pytest.approx(0.5, abs=1e-6)
