"""경로 제공자와 공공데이터 검증. 외부 네트워크는 쓰지 않는다."""

from __future__ import annotations

import httpx
import pytest

from app.config import REPO_ROOT
from app.integrations.rest_areas import load_rest_areas
from app.integrations.routing.base import MeteredRouting, RoutingError
from app.integrations.routing.cache import CachedRouting
from app.integrations.routing.estimate import EstimatedRouting
from app.integrations.routing.osrm import OsrmRouting
from app.optimization.candidates import matches_direction
from app.optimization.geo import RouteLine, angle_difference_deg, bearing_deg, haversine_m
from app.optimization.types import LatLon

from .conftest import TableRouting

A = LatLon(37.5, 127.0)
B = LatLon(37.5, 128.0)
C = LatLon(37.6, 128.5)


def osrm(handler) -> OsrmRouting:
    return OsrmRouting("http://osrm.test", transport=httpx.MockTransport(handler))


def test_osrm_route_parses_duration_distance_and_shape():
    def handler(request: httpx.Request) -> httpx.Response:
        assert "/route/v1/driving/127.000000,37.500000;128.000000,37.500000" in str(request.url)
        return httpx.Response(200, json={
            "code": "Ok",
            "routes": [{"duration": 3600, "distance": 90000, "geometry": {"coordinates": [[127.0, 37.5], [128.0, 37.5]]}}],
        })

    route = osrm(handler).route(A, B)
    assert route.duration_seconds == 3600
    assert route.distance_m == 90000
    assert route.polyline == [A, B]


def test_osrm_matrix_separates_sources_and_destinations():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params["sources"] == "0;1"
        assert request.url.params["destinations"] == "2"
        return httpx.Response(200, json={"code": "Ok", "durations": [[100], [None]]})

    assert osrm(handler).matrix([A, B], [C]) == [[100], [None]]


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(500, text="boom"),
        httpx.Response(200, text="not json"),
        httpx.Response(200, json={"code": "NoRoute"}),
        httpx.Response(200, json={"code": "Ok", "routes": []}),
    ],
)
def test_osrm_failures_raise_routing_error(response):
    with pytest.raises(RoutingError):
        osrm(lambda request: response).route(A, B)


def test_osrm_network_failure_raises_routing_error():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("timeout")

    with pytest.raises(RoutingError):
        osrm(handler).matrix([A], [B])


class CountingRouting(TableRouting):
    def __init__(self):
        super().__init__()
        self.calls = 0

    def matrix(self, origins, destinations, ttl_seconds=None):
        self.calls += 1
        return super().matrix(origins, destinations, ttl_seconds)


def test_cache_reuses_time_for_nearly_unchanged_position():
    inner = CountingRouting()
    cached = CachedRouting(inner)
    first = cached.matrix([A], [B])
    moved_slightly = LatLon(A.lat + 0.0001, A.lon)
    assert cached.matrix([moved_slightly], [B]) == first
    assert inner.calls == 1


def test_cache_fetches_again_after_real_movement():
    inner = CountingRouting()
    cached = CachedRouting(inner)
    cached.matrix([A], [B])
    cached.matrix([LatLon(A.lat + 0.1, A.lon)], [B])
    assert inner.calls == 2


def test_cache_expires_after_ttl():
    now = [0.0]
    inner = CountingRouting()
    cached = CachedRouting(inner, clock=lambda: now[0])
    cached.matrix([A], [B], ttl_seconds=10)
    now[0] = 11
    cached.matrix([A], [B], ttl_seconds=10)
    assert inner.calls == 2


def test_cache_keeps_unreachable_pairs():
    inner = CountingRouting()
    inner.blocked.add((B.lat, B.lon))
    cached = CachedRouting(inner)
    assert cached.matrix([A], [B]) == [[None]]
    assert cached.matrix([A], [B]) == [[None]]
    assert inner.calls == 1


def test_metered_routing_counts_logical_queries():
    metered = MeteredRouting(CachedRouting(TableRouting()))
    metered.route(A, B)
    metered.matrix([A, B], [C])
    metered.matrix([A, B], [C])
    assert metered.usage.route_calls == 1
    assert metered.usage.matrix_calls == 2
    assert metered.usage.logical_queries == 5


def test_estimated_routing_is_labelled_and_deterministic():
    routing = EstimatedRouting()
    assert routing.source == "ESTIMATE"
    assert routing.traffic_aware is False
    assert routing.route(A, B).duration_seconds == routing.matrix([A], [B])[0][0]


def test_haversine_matches_known_distance():
    seoul, busan = LatLon(37.5665, 126.9780), LatLon(35.1796, 129.0756)
    assert haversine_m(seoul, busan) == pytest.approx(325_000, rel=0.02)


def test_bearing_and_angle_difference():
    assert bearing_deg(A, B) == pytest.approx(90, abs=1)
    assert angle_difference_deg(350, 10) == 20


def test_route_line_locates_point_and_heading():
    line = RouteLine([A, B, LatLon(38.5, 128.0)])
    location = line.locate(LatLon(37.51, 127.5))
    assert location.distance_m == pytest.approx(1112, rel=0.02)
    assert location.heading_deg == pytest.approx(90, abs=2)
    assert 0 < location.fraction < 0.5


@pytest.fixture(scope="module")
def rest_areas():
    return load_rest_areas(REPO_ROOT / "data" / "rest_areas.csv")


def test_public_data_was_normalized_completely(rest_areas):
    assert len(rest_areas) == 215
    assert len({area.id for area in rest_areas}) == 215


def test_every_rest_area_has_usable_direction(rest_areas):
    for area in rest_areas:
        assert area.bidirectional or area.toward_point is not None, area.name


def test_coordinates_are_inside_korea(rest_areas):
    for area in rest_areas:
        assert 33 <= area.position.lat <= 39, area.name
        assert 124 <= area.position.lon <= 132, area.name


def test_toward_city_direction_separates_real_paired_rest_areas(rest_areas):
    """실제 데이터의 양방향 쌍으로 동쪽으로 달릴 때 강릉 방향만 통과하는지 확인한다."""
    by_name = {area.name: area for area in rest_areas}
    eastbound = RouteLine([LatLon(37.3, 127.5), LatLon(37.5, 128.2), LatLon(37.65, 128.6)])
    for name in ("횡성", "평창"):
        forward = by_name[f"{name}(강릉)"]
        backward = by_name[f"{name}(인천)"]
        assert matches_direction(forward, eastbound.locate(forward.position))
        assert not matches_direction(backward, eastbound.locate(backward.position))
