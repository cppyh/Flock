"""휴게소 후보 생성. 경로 API를 호출하기 전 저비용 필터로 후보를 K개까지 줄인다(design.md §8.2)."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta

from ..integrations.routing.base import Route
from .geo import RouteLine, RouteLocation, angle_difference_deg, bearing_deg, haversine_m
from .types import (
    EngineConfig,
    LatLon,
    MeetingPurpose,
    MeetWindow,
    RecommendationRequest,
    RestArea,
)

# 경로 밖 차량의 도착 시간을 어림할 때 쓰는 값. 정렬에만 쓰고 결과에는 나가지 않는다.
ROUGH_ROAD_FACTOR = 1.3
ROUGH_SPEED_MPS = 80 / 3.6
HEADING_TOLERANCE_DEG = 90

FACILITY_EXCLUDED_KINDS = {"SIMPLE", "TRUCK"}


@dataclass(frozen=True)
class CandidateSelection:
    selected: list[RestArea]
    rejected: Counter[str]
    # 장소·방향·경로 조건을 통과한 휴게소. 상위 K개에 들지 못한 곳도 포함한다.
    accessible_ids: frozenset[str]


def facility_rejection(area: RestArea, purpose: MeetingPurpose) -> str | None:
    """장소 자체의 조건으로 후보에서 뺄 이유를 돌려준다."""
    if area.kind in FACILITY_EXCLUDED_KINDS:
        return "FACILITY"
    if purpose is MeetingPurpose.MEAL and not area.has_restaurant:
        return "NO_RESTAURANT"
    return None


def matches_direction(area: RestArea, location: RouteLocation) -> bool:
    """고속도로 휴게소는 방향별로 분리되어 있어 같은 방향으로 달리는 차량만 들어갈 수 있다."""
    if area.bidirectional:
        return True
    if area.toward_point is None:
        return False
    travel_bearing = bearing_deg(area.position, area.toward_point)
    return angle_difference_deg(travel_bearing, location.heading_deg) < HEADING_TOLERANCE_DEG


def select_candidates(
    rest_areas: list[RestArea],
    routes: dict[str, Route],
    request: RecommendationRequest,
    window: MeetWindow | None,
    config: EngineConfig,
    top_k: int,
) -> CandidateSelection:
    lines = {vehicle_id: RouteLine(route.polyline) for vehicle_id, route in routes.items()}
    rejected: Counter[str] = Counter()
    scored: list[tuple[float, RestArea]] = []

    for area in rest_areas:
        reason = facility_rejection(area, request.purpose)
        if reason:
            rejected[reason] += 1
            continue
        nearby = {
            vehicle_id: location
            for vehicle_id, line in lines.items()
            if (location := line.locate(area.position)).distance_m <= config.candidate_buffer_m
        }
        if not nearby:
            rejected["OFF_ROUTE"] += 1
            continue
        if not all(matches_direction(area, location) for location in nearby.values()):
            rejected["OPPOSITE_DIRECTION"] += 1
            continue
        scored.append((_rough_cost(area, nearby, routes, request, window), area))

    scored.sort(key=lambda item: item[0])
    accessible_ids = frozenset(area.id for _, area in scored)
    return CandidateSelection([area for _, area in scored[:top_k]], rejected, accessible_ids)


def _rough_cost(
    area: RestArea,
    nearby: dict[str, RouteLocation],
    routes: dict[str, Route],
    request: RecommendationRequest,
    window: MeetWindow | None,
) -> float:
    """직선거리 기반 간이 점수. 시간대 밖 후보가 상위 K개를 차지하지 않도록 벌점을 더한다."""
    destination = request.destination.position
    detour_km = 0.0
    meet_time = request.now
    for vehicle in request.vehicles:
        to_area = haversine_m(vehicle.position, area.position)
        after_area = haversine_m(area.position, destination)
        direct = haversine_m(vehicle.position, destination)
        detour_km += max(0.0, to_area + after_area - direct) / 1000
        arrival = vehicle.measured_at + timedelta(
            seconds=_rough_travel_seconds(vehicle.vehicle_id, vehicle.position, area.position, nearby, routes)
        )
        meet_time = max(meet_time, arrival)
    return detour_km + _minutes_outside(meet_time, window)


def _rough_travel_seconds(
    vehicle_id: str,
    origin: LatLon,
    target: LatLon,
    nearby: dict[str, RouteLocation],
    routes: dict[str, Route],
) -> float:
    location = nearby.get(vehicle_id)
    if location is not None:
        return routes[vehicle_id].duration_seconds * location.fraction
    return haversine_m(origin, target) * ROUGH_ROAD_FACTOR / ROUGH_SPEED_MPS


def _minutes_outside(moment: datetime, window: MeetWindow | None) -> float:
    if window is None:
        return 0.0
    if window.start is not None and moment < window.start:
        return (window.start - moment).total_seconds() / 60
    if window.end is not None and moment > window.end:
        return (moment - window.end).total_seconds() / 60
    return 0.0
