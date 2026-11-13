"""design.md §14.1 Algorithm sanity와 §14.2 실패 케이스."""

from __future__ import annotations

from dataclasses import replace
from datetime import timedelta

import pytest

from app.integrations.routing.base import RoutingError
from app.optimization.baselines import BaselineEvaluator
from app.optimization.engine import DESTINATION_NOTICE, KEEP_CURRENT_NOTICE, RecommendationEngine, is_open_at
from app.optimization.types import (
    Destination,
    EngineConfig,
    LatLon,
    MeetingPurpose,
    RecommendationRequest,
    VehicleInput,
)

from .conftest import GOAL, NOW, SEOUL, TableRouting, make_area

CONFIG = EngineConfig(candidate_buffer_m=2000)


def request(purpose: MeetingPurpose = MeetingPurpose.ANY, **overrides) -> RecommendationRequest:
    values = dict(
        vehicles=(
            VehicleInput("a", SEOUL, NOW),
            VehicleInput("b", LatLon(37.5, 127.1), NOW),
        ),
        destination=Destination("목적지", GOAL),
        purpose=purpose,
        now=NOW,
        departure_at=NOW,
    )
    values.update(overrides)
    return RecommendationRequest(**values)


def engine(areas, routing=None, config=CONFIG) -> RecommendationEngine:
    return RecommendationEngine(routing or TableRouting(), areas, config)


def ids(result) -> list[str]:
    return [item.metrics.candidate.id for item in result.top]


def test_opposite_direction_rest_area_is_excluded():
    areas = [make_area("east", 128.0), make_area("west", 128.0, toward_east=False)]
    result = engine(areas).recommend(request())
    assert "east" in ids(result)
    assert "west" not in ids(result)
    assert result.rejected["OPPOSITE_DIRECTION"] == 1


def test_bidirectional_rest_area_is_allowed_for_either_direction():
    area = make_area("both", 128.0, bidirectional=True, toward=None, toward_point=None)
    assert "both" in ids(engine([area]).recommend(request()))


def test_rest_area_without_direction_data_is_excluded():
    area = make_area("unknown", 128.0, toward=None, toward_point=None)
    assert "unknown" not in ids(engine([area]).recommend(request()))


def test_vehicles_travelling_opposite_ways_reject_one_way_rest_area():
    """한 차량이 지나가도 다른 차량이 반대 방향이면 그룹 전체가 들어갈 수 없다."""
    vehicles = (VehicleInput("a", SEOUL, NOW), VehicleInput("b", LatLon(37.5, 130.0), NOW))
    goal = Destination("가운데", LatLon(37.5, 128.5))
    result = engine([make_area("east", 128.0)]).recommend(request(vehicles=vehicles, destination=goal))
    assert "east" not in ids(result)


def test_off_route_rest_area_is_excluded():
    result = engine([make_area("far", 128.0, lat=36.0)]).recommend(request())
    assert "far" not in ids(result)
    assert result.rejected["OFF_ROUTE"] == 1


@pytest.mark.parametrize(
    ("kind", "reason"), [("SIMPLE", "FACILITY"), ("TRUCK", "FACILITY")]
)
def test_short_stop_and_truck_facilities_are_excluded(kind, reason):
    result = engine([make_area("x", 128.0, kind=kind)]).recommend(request())
    assert result.rejected[reason] == 1


def test_destination_is_always_a_comparison_candidate():
    result = engine([make_area("east", 128.0)]).recommend(request())
    assert result.destination.metrics.candidate.is_destination
    assert result.destination.metrics.total_detour_minutes == 0


def test_notice_is_shown_when_destination_ranks_first():
    area = make_area("costly", 127.9, lat=37.5001)
    routing = TableRouting()
    far_config = replace(CONFIG, epsilon=0.0, delta=0.0)
    # 후보를 들르면 차량 b가 크게 돌아가도록 만들어 목적지가 1위가 되게 한다.
    routing_matrix = routing.matrix
    routing.matrix = lambda o, d, ttl_seconds=None: [
        [value + 900 if len(d) == 1 and d[0].lon == 127.9 else value for value in row]
        for row in routing_matrix(o, d, ttl_seconds)
    ]
    result = engine([area], routing, far_config).recommend(request())
    assert result.top[0].metrics.candidate.is_destination
    assert result.notice == DESTINATION_NOTICE


def test_no_candidates_falls_back_to_destination():
    result = engine([]).recommend(request())
    assert ids(result) == ["destination"]
    assert result.notice == DESTINATION_NOTICE


def test_rest_window_keeps_only_candidates_in_range():
    # 경도 1도 = 60분. 마지막 차량(127.0) 기준 90~150분은 경도 128.5 이후다.
    areas = [make_area("early", 127.5), make_area("in-range", 128.7)]
    result = engine(areas).recommend(request(MeetingPurpose.REST))
    assert result.window is not None
    assert "in-range" in ids(result)
    assert "early" not in ids(result)
    assert result.rejected["OUTSIDE_WINDOW"] == 1


def test_depart_together_keeps_only_first_hour():
    areas = [make_area("soon", 127.6), make_area("later", 128.5)]
    result = engine(areas).recommend(request(MeetingPurpose.DEPART_TOGETHER))
    assert "soon" in ids(result)
    assert "later" not in ids(result)


def test_meal_requires_restaurant_and_time_range():
    meal_time = NOW + timedelta(minutes=60)
    areas = [make_area("food", 128.0), make_area("no-food", 128.01, has_restaurant=False)]
    result = engine(areas).recommend(request(MeetingPurpose.MEAL, meal_time=meal_time))
    assert "food" in ids(result)
    assert "no-food" not in ids(result)
    assert result.rejected["NO_RESTAURANT"] == 1


def test_meal_without_time_is_a_programming_error():
    with pytest.raises(ValueError):
        engine([]).recommend(request(MeetingPurpose.MEAL))


def test_window_expands_once_before_giving_up():
    # 합류 시각이 60분보다 조금 늦은 후보. 30분 확장에는 들어온다.
    result = engine([make_area("near-limit", 128.2)]).recommend(request(MeetingPurpose.DEPART_TOGETHER))
    assert result.window_expanded is True
    assert "near-limit" in ids(result)


def test_empty_window_after_expansion_recommends_destination():
    result = engine([make_area("too-late", 128.9)]).recommend(request(MeetingPurpose.DEPART_TOGETHER))
    assert result.window_expanded is True
    assert ids(result) == ["destination"]
    assert result.notice == DESTINATION_NOTICE


def test_destination_is_exempt_from_time_window():
    result = engine([]).recommend(request(MeetingPurpose.DEPART_TOGETHER))
    assert result.destination.metrics.meet_time > NOW + timedelta(hours=1)
    assert ids(result) == ["destination"]


def test_personal_detour_limit_excludes_candidate():
    routing = TableRouting()
    original = routing.matrix
    routing.matrix = lambda o, d, ttl_seconds=None: [
        [value + 1500 if len(d) > 1 or d[0].lon == 128.0 else value for value in row]
        for row in original(o, d, ttl_seconds)
    ]
    result = engine([make_area("far-detour", 128.0)], routing).recommend(request())
    assert "far-detour" not in ids(result)
    assert result.rejected["DETOUR_LIMIT"] == 1


def test_candidate_unreachable_by_any_vehicle_is_excluded():
    routing = TableRouting()
    routing.blocked.add((37.5, 128.0))
    result = engine([make_area("blocked", 128.0)], routing).recommend(request())
    assert "blocked" not in ids(result)
    assert result.rejected["PARTIAL_ACCESS"] == 1


def test_closed_rest_area_is_excluded():
    area = make_area("day-only", 128.0, open_start="08:00", open_end="08:30")
    result = engine([area]).recommend(request())
    assert "day-only" not in ids(result)
    assert result.rejected["CLOSED"] == 1


def test_opening_hours_support_overnight_ranges():
    area = make_area("night", 128.0, open_start="22:00", open_end="06:00")
    assert is_open_at(area, NOW.replace(hour=14))  # 23:00 KST
    assert not is_open_at(area, NOW.replace(hour=3))  # 12:00 KST


def test_routing_failure_raises_instead_of_inventing_eta():
    with pytest.raises(RoutingError):
        engine([make_area("east", 128.0)], TableRouting(failing=True)).recommend(request())


def test_top_k_limits_detailed_route_queries():
    areas = [make_area(f"area{i}", 127.5 + i * 0.1) for i in range(10)]
    result = engine(areas).recommend(request(), top_k=3)
    assert result.candidates_evaluated == 3


def test_query_count_matches_documented_formula():
    """design.md §8.5: n + n·K + K."""
    areas = [make_area(f"area{i}", 127.5 + i * 0.1) for i in range(10)]
    result = engine(areas).recommend(request(), top_k=4)
    assert result.usage.logical_queries == 2 + 2 * 4 + 4


def test_top_three_only():
    areas = [make_area(f"area{i}", 127.5 + i * 0.1) for i in range(10)]
    assert len(engine(areas).recommend(request()).top) == 3


def test_replanning_keeps_current_point_without_enough_improvement():
    areas = [make_area("current", 128.0), make_area("similar", 128.1)]
    result = engine(areas).recommend(request(current_place_id="current"))
    assert result.change_recommended is False
    assert result.notice == KEEP_CURRENT_NOTICE


def test_replanning_suggests_change_when_clearly_better():
    routing = TableRouting()
    original = routing.matrix
    routing.matrix = lambda o, d, ttl_seconds=None: [
        [
            value + 700 if len(d) > 1 and d[column].lon == 128.0 else value
            for column, value in enumerate(row)
        ]
        for row in original(o, d, ttl_seconds)
    ]
    areas = [make_area("current", 128.0), make_area("better", 128.1)]
    result = engine(areas, routing).recommend(request(current_place_id="current"))
    assert result.top[0].metrics.candidate.id == "better"
    assert result.change_recommended is True


def test_current_point_failing_direction_filter_is_not_recommended():
    """비교하려고 지표는 계산하지만 갈 수 없는 곳을 추천하거나 유지를 권하면 안 된다."""
    area = make_area("wrong-way", 128.0, toward_east=False)
    result = engine([area]).recommend(request(current_place_id="wrong-way"))
    assert "wrong-way" not in ids(result)
    assert result.current is not None
    assert result.change_recommended is True
    assert result.notice != KEEP_CURRENT_NOTICE


def test_current_point_failing_facility_filter_is_not_recommended():
    area = make_area("truck-stop", 128.0, kind="TRUCK")
    result = engine([area]).recommend(request(current_place_id="truck-stop"))
    assert "truck-stop" not in ids(result)
    assert result.change_recommended is True


def test_current_point_outside_detour_limit_is_replaced():
    routing = TableRouting()
    original = routing.matrix
    routing.matrix = lambda o, d, ttl_seconds=None: [
        [
            value + 1500 if len(d) > 1 and d[column].lon == 128.0 else value
            for column, value in enumerate(row)
        ]
        for row in original(o, d, ttl_seconds)
    ]
    areas = [make_area("current", 128.0), make_area("other", 128.1)]
    result = engine(areas, routing).recommend(request(current_place_id="current"))
    assert "current" not in ids(result)
    assert result.change_recommended is True


def test_current_point_outside_top_k_stays_recommendable():
    """상위 K개에 못 들었을 뿐 조건을 통과한 곳은 계속 후보다."""
    areas = [make_area(f"area{i}", 127.5 + i * 0.1) for i in range(10)]
    result = engine(areas).recommend(request(current_place_id="area9"), top_k=2)
    assert result.change_recommended is not None
    assert result.current is not None
    assert result.candidates_evaluated == 3


def test_vanished_current_point_asks_for_change():
    result = engine([make_area("east", 128.0)]).recommend(request(current_place_id="removed-from-data"))
    assert result.change_recommended is True


def test_baselines_are_all_evaluated_on_the_same_request():
    areas = [make_area("near-start", 127.3), make_area("middle", 128.0), make_area("near-end", 128.8)]
    rows = BaselineEvaluator(TableRouting(), areas, CONFIG).evaluate(request())
    assert [row.method.split()[0] for row in rows] == ["B0", "B1", "B2", "B3", "Ours"]
    assert all(row.metrics is not None for row in rows)
    assert rows[0].metrics.candidate.is_destination
