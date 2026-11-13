"""전기차 충전 연계: 합류 지점에서 기다리는 시간을 충전 시간으로 쓴다."""

from __future__ import annotations

import pytest

from app.optimization.ev import EvConfig, charge_minutes, percent_used, plan_ev
from app.optimization.scoring import compute_metrics, score_candidates
from app.optimization.types import Candidate, EngineConfig, LatLon, MeetingPurpose, VehicleInput
from app.services.recommendations import build_reason

from .conftest import NOW, auth, create_group, join_group, send_location, share_location, make_area

CONFIG = EvConfig()
HOUR = 3600
GANGNEUNG = {"name": "강릉역", "lat": 37.7640, "lon": 128.8996}


# ---- 충전 계획 ----


def test_energy_model_uses_average_speed_and_consumption():
    # 1시간 = 80km, 0.18kWh/km = 14.4kWh, 77.4kWh 배터리의 약 18.6%
    assert percent_used(HOUR, CONFIG) == pytest.approx(18.605, abs=0.01)
    # 10%p = 7.74kWh, 100kW로 약 4.6분
    assert charge_minutes(20, 30, CONFIG) == pytest.approx(4.644, abs=0.01)
    assert charge_minutes(50, 40, CONFIG) == 0


def test_charging_fits_inside_the_wait():
    plan = plan_ev(30, HOUR, HOUR / 2, wait_minutes=10, has_charger=True, config=CONFIG)
    assert plan.needs_charge
    assert plan.battery_at_arrival == pytest.approx(11.4, abs=0.1)
    assert plan.charge_minutes == pytest.approx(8.3, abs=0.1)
    assert plan.covered_minutes == pytest.approx(plan.charge_minutes)
    assert plan.extra_minutes == 0


def test_charging_longer_than_the_wait_makes_the_group_wait_the_rest():
    plan = plan_ev(30, HOUR, HOUR / 2, wait_minutes=3, has_charger=True, config=CONFIG)
    assert plan.covered_minutes == 3
    assert plan.extra_minutes == pytest.approx(plan.charge_minutes - 3)


def test_no_charger_means_a_separate_stop():
    plan = plan_ev(30, HOUR, HOUR / 2, wait_minutes=10, has_charger=False, config=CONFIG)
    assert plan.needs_charge and not plan.charger_available
    assert plan.covered_minutes == 0
    assert plan.extra_minutes == pytest.approx(plan.charge_minutes + CONFIG.separate_stop_minutes)


def test_battery_too_low_to_reach_the_meeting_point_needs_a_stop_first():
    plan = plan_ev(10, HOUR, HOUR / 2, wait_minutes=30, has_charger=True, config=CONFIG)
    assert plan.battery_at_arrival < 0
    assert plan.covered_minutes == 0
    assert plan.extra_minutes > CONFIG.separate_stop_minutes


def test_enough_battery_offers_a_top_up_while_waiting():
    plan = plan_ev(60, HOUR, HOUR / 2, wait_minutes=12, has_charger=True, config=CONFIG)
    assert not plan.needs_charge
    assert plan.extra_minutes == 0 and plan.covered_minutes == 0
    assert plan.bonus_percent == pytest.approx(12 / 60 * 100 / 77.4 * 100, abs=0.1)


def test_top_up_stops_at_the_fast_charge_limit():
    plan = plan_ev(95, HOUR / 2, HOUR / 2, wait_minutes=30, has_charger=True, config=CONFIG)
    assert plan.bonus_percent == pytest.approx(max(0, 80 - plan.battery_at_arrival), abs=0.01)


def test_short_wait_offers_no_top_up():
    assert plan_ev(60, HOUR, 0, wait_minutes=2, has_charger=True, config=CONFIG).bonus_percent == 0


# ---- 점수 ----


def candidates(battery: float | None):
    vehicles = (
        VehicleInput("ev", LatLon(37.5, 127.0), NOW, battery),
        VehicleInput("car", LatLon(37.5, 127.1), NOW),
    )
    fast = Candidate("fast", "fast", LatLon(37.5, 128.0), make_area("fast", 128.0, has_ev_charger=False))
    charge = Candidate("charge", "charge", LatLon(37.5, 128.0), make_area("charge", 128.0))
    base = {"ev": 5400, "car": 5400}
    # fast: 둘이 동시에 도착한다. charge: 전기차가 10분 먼저 도착해 기다린다(충전기 있음).
    fast_metrics = compute_metrics(fast, vehicles, {"ev": 3600, "car": 3600}, 1800, base)
    charge_metrics = compute_metrics(charge, vehicles, {"ev": 3000, "car": 3600}, 1800, {"ev": 4800, "car": 5400})
    return fast_metrics, charge_metrics


def test_without_battery_data_scoring_is_unchanged():
    fast, charge = candidates(None)
    assert fast.ev_plans == {} and charge.ev_plans == {}
    assert charge.effective_wait_minutes == charge.total_wait_minutes
    assert charge.ev_extra_minutes == 0
    ranked = score_candidates([fast, charge], EngineConfig(), MeetingPurpose.ANY)
    assert ranked[0].metrics.candidate.id == "fast"


def test_low_battery_prefers_the_charger_where_waiting_becomes_charging():
    fast, charge = candidates(30)
    assert fast.ev_extra_minutes > 10
    assert charge.ev_extra_minutes == 0
    assert charge.ev_covered_minutes > 5
    ranked = score_candidates([fast, charge], EngineConfig(), MeetingPurpose.ANY)
    assert ranked[0].metrics.candidate.id == "charge"


def test_full_battery_keeps_the_time_based_choice():
    fast, charge = candidates(95)
    ranked = score_candidates([fast, charge], EngineConfig(), MeetingPurpose.ANY)
    assert ranked[0].metrics.candidate.id == "fast"


def test_group_delay_counts_everyone_waiting_for_the_charge():
    fast, _ = candidates(30)
    assert fast.group_delay_minutes == pytest.approx(
        fast.total_detour_minutes + fast.total_wait_minutes + 2 * fast.ev_extra_minutes
    )


def test_reason_explains_the_charging_plan():
    fast, charge = candidates(30)
    assert "대기와 겹침" in build_reason(charge) or "대기 중 끝남" in build_reason(charge)
    assert "충전소 없어 따로 충전" in build_reason(fast)
    _, roomy = candidates(60)
    assert "대기 중 충전 +" in build_reason(roomy)


# ---- API ----


def group_url(grant: dict, suffix: str = "") -> str:
    return f"/api/v1/groups/{grant['group']['group_id']}{suffix}"


def participant(client, grant: dict) -> dict:
    group = client.get(group_url(grant), headers=auth(grant)).json()
    return next(p for p in group["participants"] if p["vehicle_id"] == grant["vehicle_id"])


def test_battery_level_is_shared_with_the_location(client):
    leader = create_group(client)
    share_location(client, leader, 37.5665, 126.978)
    assert send_location(client, leader, 37.5666, 126.978, battery_percent=42).status_code == 202
    assert participant(client, leader)["battery_percent"] == 42


def test_location_without_battery_keeps_the_last_level(client):
    leader = create_group(client)
    share_location(client, leader, 37.5665, 126.978)
    assert send_location(client, leader, 37.5666, 126.978, battery_percent=42).json()["accepted"]
    assert send_location(client, leader, 37.5667, 126.978).json()["accepted"]
    me = participant(client, leader)
    assert me["lat"] == 37.5667
    assert me["battery_percent"] == 42


def test_stopping_sharing_erases_the_battery_level(client):
    leader = create_group(client)
    share_location(client, leader, 37.5665, 126.978)
    send_location(client, leader, 37.5666, 126.978, battery_percent=42)
    client.patch(group_url(leader, "/location-sharing"), json={"enabled": False}, headers=auth(leader))
    assert participant(client, leader)["battery_percent"] is None


def test_invalid_battery_level_is_rejected(client):
    leader = create_group(client)
    share_location(client, leader, 37.5665, 126.978)
    assert send_location(client, leader, 37.5666, 126.978, battery_percent=120).status_code == 422


def test_recommendations_carry_the_charging_plan_for_electric_vehicles(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    client.patch(group_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader))
    share_location(client, leader, 37.5665, 126.978)
    send_location(client, leader, 37.5666, 126.978, battery_percent=25)
    share_location(client, member, 37.2636, 127.0286)
    result = client.post(group_url(leader, "/recommendations"), headers=auth(leader)).json()
    for item in [*result["recommendations"], result["destination_comparison"]]:
        plan = item["ev_plan"][leader["vehicle_id"]]
        assert plan["battery_percent"] == 25
        assert set(plan) >= {"needs_charge", "charge_minutes", "covered_by_wait_minutes", "extra_wait_minutes"}
        assert member["vehicle_id"] not in item["ev_plan"]


def test_recommendations_without_electric_vehicles_have_no_plan(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    client.patch(group_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader))
    share_location(client, leader, 37.5665, 126.978)
    share_location(client, member, 37.2636, 127.0286)
    result = client.post(group_url(leader, "/recommendations"), headers=auth(leader)).json()
    assert all(item["ev_plan"] is None for item in result["recommendations"])
    assert result["destination_comparison"]["ev_extra_minutes"] == 0
