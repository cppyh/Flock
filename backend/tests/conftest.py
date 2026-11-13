from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

os.environ.setdefault("DATABASE_URL", "sqlite://")

from fastapi.testclient import TestClient  # noqa: E402

from app.config import Settings  # noqa: E402
from app.integrations.routing.base import Matrix, Route, RoutingError  # noqa: E402
from app.main import create_app  # noqa: E402
from app.optimization.types import LatLon, RestArea  # noqa: E402

NOW = datetime(2026, 11, 7, 0, 0, tzinfo=timezone.utc)
SEOUL = LatLon(37.5, 127.0)
GOAL = LatLon(37.5, 129.0)
EAST_POINT = LatLon(37.5, 140.0)
WEST_POINT = LatLon(37.5, 120.0)


def make_area(area_id: str, lon: float, *, lat: float = 37.5, toward_east: bool = True, **overrides) -> RestArea:
    values = dict(
        id=area_id,
        name=area_id,
        route_name="시험선",
        position=LatLon(lat, lon),
        kind="GENERAL",
        bidirectional=False,
        toward="동쪽" if toward_east else "서쪽",
        toward_point=EAST_POINT if toward_east else WEST_POINT,
        open_start="00:00",
        open_end="23:59",
        parking_spaces=200,
        has_restaurant=True,
        has_ev_charger=True,
        has_gas_station=True,
        has_toilet=True,
        has_store=True,
        data_date="2026-09-17",
    )
    values.update(overrides)
    return RestArea(**values)


class TableRouting:
    """좌표를 경도 기준 시간으로 바꾸는 시험용 제공자. 동서 직선 도로를 흉내 낸다."""

    source = "TEST"
    traffic_aware = False

    def __init__(self, seconds_per_degree: float = 3600, failing: bool = False):
        self.seconds_per_degree = seconds_per_degree
        self.failing = failing
        self.blocked: set[tuple[float, float]] = set()

    def route(self, origin: LatLon, destination: LatLon) -> Route:
        self._maybe_fail()
        steps = 20
        polyline = [
            LatLon(
                origin.lat + (destination.lat - origin.lat) * step / steps,
                origin.lon + (destination.lon - origin.lon) * step / steps,
            )
            for step in range(steps + 1)
        ]
        return Route(self._seconds(origin, destination), 1000.0, polyline)

    def matrix(self, origins, destinations, ttl_seconds=None) -> Matrix:
        self._maybe_fail()
        return [
            [
                None if (d.lat, d.lon) in self.blocked else self._seconds(o, d)
                for d in destinations
            ]
            for o in origins
        ]

    def _seconds(self, origin: LatLon, destination: LatLon) -> float:
        return (abs(origin.lon - destination.lon) + abs(origin.lat - destination.lat)) * self.seconds_per_degree

    def _maybe_fail(self) -> None:
        if self.failing:
            raise RoutingError("시험용 경로 오류")


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        _env_file=None,
        database_url=f"sqlite:///{tmp_path / 'test.db'}",
        token_secret="test-secret-test-secret-test-secret-1234",
        routing_provider="estimate",
        candidate_buffer_km=15,
        join_attempts_per_minute=1000,
    )


@pytest.fixture
def client(settings: Settings):
    with TestClient(create_app(settings)) as test_client:
        yield test_client


def auth(grant: dict) -> dict[str, str]:
    return {"Authorization": f"Bearer {grant['access_token']}"}


def create_group(client: TestClient, nickname: str = "서울 차량") -> dict:
    response = client.post("/api/v1/groups", json={"name": "강릉 여행", "nickname": nickname})
    assert response.status_code == 201, response.text
    return response.json()


def join_group(client: TestClient, code: str, nickname: str = "수원 차량") -> dict:
    response = client.post("/api/v1/groups/join", json={"invite_code": code, "nickname": nickname})
    assert response.status_code == 201, response.text
    return response.json()


def share_location(client: TestClient, grant: dict, lat: float, lon: float, *, age_seconds: float = 0) -> None:
    group_id = grant["group"]["group_id"]
    response = client.patch(
        f"/api/v1/groups/{group_id}/location-sharing", json={"enabled": True}, headers=auth(grant)
    )
    assert response.status_code == 200, response.text
    send_location(client, grant, lat, lon, age_seconds=age_seconds)


def send_location(client: TestClient, grant: dict, lat: float, lon: float, *, age_seconds: float = 0, **extra):
    group_id = grant["group"]["group_id"]
    timestamp = datetime.now(timezone.utc) - timedelta(seconds=age_seconds)
    return client.post(
        f"/api/v1/groups/{group_id}/locations",
        json={"lat": lat, "lon": lon, "timestamp": timestamp.isoformat(), **extra},
        headers=auth(grant),
    )
