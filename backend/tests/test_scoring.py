"""design.md §14.1 Backend unit: 점수·정규화·측정 시각·가중치."""

from __future__ import annotations

from datetime import timedelta

import pytest

from app.optimization.scoring import compute_metrics, normalize, score_candidates, suitability_penalty
from app.optimization.types import Candidate, EngineConfig, LatLon, MeetingPurpose, VehicleInput

from .conftest import NOW, make_area

VEHICLES = (
    VehicleInput("a", LatLon(37.5, 127.0), NOW),
    VehicleInput("b", LatLon(37.5, 127.1), NOW),
)


def metrics(candidate_id: str, to_a: float, to_b: float, after: float, base_a: float = 7200, base_b: float = 7200):
    candidate = Candidate(candidate_id, candidate_id, LatLon(37.5, 128.0), make_area(candidate_id, 128.0))
    return compute_metrics(candidate, VEHICLES, {"a": to_a, "b": to_b}, after, {"a": base_a, "b": base_b})


def test_normalize_scales_to_unit_range():
    assert normalize([10, 20, 30], 3) == [0.0, 0.5, 1.0]


def test_normalize_treats_small_differences_as_zero():
    assert normalize([10, 11, 12.9], 3) == [0.0, 0.0, 0.0]


def test_normalize_handles_identical_and_empty_values():
    assert normalize([5, 5], 3) == [0.0, 0.0]
    assert normalize([], 3) == []


def test_metrics_follow_definitions():
    item = metrics("p", to_a=3600, to_b=4200, after=3600)
    assert item.detour_minutes == {"a": 0.0, "b": 10.0}
    assert item.wait_minutes == {"a": 10.0, "b": 0.0}
    assert item.meet_time == NOW + timedelta(seconds=4200)
    assert item.total_detour_minutes == 10
    assert item.total_wait_minutes == 10
    assert item.max_detour_minutes == 10
    assert item.shared_minutes == 60


def test_detour_plus_wait_equals_arrival_delay_when_not_clamped():
    """design.md §8.3: detour + wait = meet_time + T_after − T_base."""
    item = metrics("p", to_a=4000, to_b=4600, after=3600)
    for vehicle in ("a", "b"):
        delay = (item.meet_time - NOW).total_seconds() + 3600 - 7200
        assert item.detour_minutes[vehicle] + item.wait_minutes[vehicle] == pytest.approx(delay / 60)


def test_arrival_uses_measurement_time_not_request_time():
    """서로 다른 시각에 측정한 위치를 같은 기준으로 비교한다."""
    earlier = NOW - timedelta(minutes=10)
    vehicles = (
        VehicleInput("a", LatLon(37.5, 127.0), earlier),
        VehicleInput("b", LatLon(37.5, 127.1), NOW),
    )
    candidate = Candidate("p", "p", LatLon(37.5, 128.0), make_area("p", 128.0))
    item = compute_metrics(candidate, vehicles, {"a": 3600, "b": 3600}, 3600, {"a": 7200, "b": 7200})
    assert item.arrivals["a"] == NOW + timedelta(minutes=50)
    assert item.arrivals["b"] == NOW + timedelta(minutes=60)
    assert item.wait_minutes["a"] == 10


def test_lower_score_is_better_and_shared_time_rewards_early_meeting():
    early = metrics("early", 1800, 1800, 5400)
    late = metrics("late", 5400, 5400, 1800)
    ranked = score_candidates([late, early], EngineConfig(), MeetingPurpose.ANY)
    assert ranked[0].metrics.candidate.id == "early"
    assert ranked[0].score < ranked[1].score


def test_candidates_do_not_tie_after_adding_shared_term():
    """design.md §8.3: 경로 위 후보들이 동점이 되는 문제를 동행 보상이 풀어야 한다."""
    items = [metrics(str(i), 1800 * i, 1800 * i, 7200 - 1800 * i) for i in (1, 2, 3)]
    scores = [s.score for s in score_candidates(items, EngineConfig(), MeetingPurpose.ANY)]
    assert len(set(scores)) == 3


def test_without_shared_term_on_route_candidates_tie():
    items = [metrics(str(i), 1800 * i, 1800 * i, 7200 - 1800 * i) for i in (1, 2, 3)]
    ranked = score_candidates(items, EngineConfig(), MeetingPurpose.ANY, include_shared=False)
    assert {s.score for s in ranked} == {0.0}


def test_depart_together_raises_shared_weight():
    config = EngineConfig()
    assert config.epsilon_for(MeetingPurpose.DEPART_TOGETHER) > config.epsilon_for(MeetingPurpose.ANY)


def test_weights_apply_as_documented():
    worst = metrics("worst", 7200, 8400, 3600)
    best = metrics("best", 3600, 3600, 3600)
    ranked = score_candidates([worst, best], EngineConfig(), MeetingPurpose.ANY)
    # detour·wait·max_detour가 모두 1로 정규화되고 동행 시간과 장소 항목은 같다.
    assert ranked[1].score == pytest.approx(1.0 + 0.7 + 0.5)


def test_suitability_rewards_parking_and_facilities():
    rich, _ = suitability_penalty(make_area("rich", 128.0))
    poor, _ = suitability_penalty(
        make_area("poor", 128.0, parking_spaces=20, has_restaurant=False, has_ev_charger=False)
    )
    assert rich < poor
    assert 0 <= rich <= 1 and 0 <= poor <= 1


def test_unknown_parking_is_marked_uncertain():
    _, uncertain = suitability_penalty(make_area("unknown", 128.0, parking_spaces=None))
    assert uncertain is True


def test_destination_has_no_place_penalty():
    assert suitability_penalty(None) == (0.0, False)
