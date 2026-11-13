"""시뮬레이터의 시연 옵션과 실패 안내를 서버 없이 확인한다."""

from __future__ import annotations

import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "simulator"))

from vehicle_simulator import (  # noqa: E402
    SimulatedVehicle,
    Simulation,
    explain_join_failure,
    parse_args,
    quiet_connection_reset,
)

SCENARIO = {"name": "시험", "vehicles": []}
GROUP_WITH_DESTINATION = {"destination": {"lat": 37.76, "lon": 128.89}, "meeting_point": None}
GROUP_WITH_POINT = {
    "destination": {"lat": 37.76, "lon": 128.89},
    "meeting_point": {"latitude": 37.60, "longitude": 128.45},
}


def simulation(*extra: str) -> Simulation:
    return Simulation(parse_args(["--scenario", "unused.json", *extra]), SCENARIO)


def vehicle() -> SimulatedVehicle:
    return SimulatedVehicle("B", "수원", (37.26, 127.02))


def test_vehicle_drives_to_destination_before_selection_by_default():
    car = vehicle()
    simulation()._retarget(car, GROUP_WITH_DESTINATION)
    assert car.target == (37.76, 128.89)


def test_hold_keeps_vehicle_at_start_until_a_point_is_selected():
    car = vehicle()
    run = simulation("--hold-until-selected")
    run._retarget(car, GROUP_WITH_DESTINATION)
    assert car.target is None

    run._retarget(car, GROUP_WITH_POINT)
    assert car.target == (37.60, 128.45)


def test_member_of_private_drive_waits_for_the_meeting_point():
    car = vehicle()
    simulation()._retarget(car, {"destination": None, "destination_hidden": True, "meeting_point": None})
    assert car.target is None


def test_electric_vehicle_drains_while_driving_and_charges_while_waiting_at_a_charger():
    car = SimulatedVehicle("B", "수원 전기차", (37.26, 127.02), battery_percent=50)
    run = simulation()
    # 동쪽으로 약 44km 떨어진 충전기 있는 휴게소
    run._retarget(car, {"destination": None, "meeting_point": {"latitude": 37.26, "longitude": 127.52, "has_ev_charger": True}})
    car.step(10_000, 400)  # 10km: 0.18kWh/km × 10 / 77.4kWh ≈ 2.3%
    assert not car.arrived
    assert 47.5 < car.battery_percent < 48
    car.step(100_000, 400)  # 남은 거리만 가서 도착한다
    assert car.arrived
    assert 39 < car.battery_percent < 41
    before = car.battery_percent
    car.step(0, 600)  # 10분, 100kW ≈ 21.5%
    assert car.battery_percent - before > 20
    assert car.location_payload()["battery_percent"] == round(car.battery_percent, 1)


def test_charging_stops_at_the_fast_charge_limit_and_needs_a_charger():
    car = SimulatedVehicle("B", "수원 전기차", (37.26, 127.12), battery_percent=78)
    simulation()._retarget(car, {"destination": None, "meeting_point": {"latitude": 37.26, "longitude": 127.12, "has_ev_charger": True}})
    car.step(0, 0)
    car.arrived = True
    car.step(0, 3600)
    assert car.battery_percent == 80
    plain = SimulatedVehicle("C", "인천", (37.26, 127.12), battery_percent=40, target=(37.26, 127.12), arrived=True)
    plain.step(0, 3600)
    assert plain.battery_percent == 40


class RecordingLoop:
    def __init__(self) -> None:
        self.reported: list[dict] = []

    def default_exception_handler(self, context: dict) -> None:
        self.reported.append(context)


def test_connection_reset_at_session_end_is_not_reported_but_other_errors_are():
    loop = RecordingLoop()
    quiet_connection_reset(loop, {"message": "connection lost", "exception": ConnectionResetError(10054)})
    assert loop.reported == []
    quiet_connection_reset(loop, {"message": "real bug", "exception": ValueError("x")})
    assert [c["message"] for c in loop.reported] == ["real bug"]


def test_vehicle_without_battery_sends_no_battery_field():
    assert "battery_percent" not in vehicle().location_payload()


def test_full_group_message_says_what_to_do_next():
    response = httpx.Response(409, json={"error": {"code": "GROUP_FULL", "message": "정원이 찼습니다."}})
    message = explain_join_failure(response)
    assert "정원이 찼습니다." in message
    assert "그룹을 새로 만든" in message


def test_other_join_failure_keeps_the_server_reason():
    response = httpx.Response(404, json={"error": {"code": "INVITE_NOT_FOUND", "message": "코드가 없습니다."}})
    assert explain_join_failure(response) == "그룹에 참여하지 못했습니다(INVITE_NOT_FOUND): 코드가 없습니다."


def test_join_failure_without_json_body_is_still_explained():
    response = httpx.Response(502, text="bad gateway")
    assert "502" in explain_join_failure(response)
