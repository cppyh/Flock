"""design.md §14.1 Backend integration과 §14.2 필수 실패 케이스."""

from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.models.orm import Group, Participant
from app.models.schemas import DestinationRequest, LocationUpdate
from app.services.tokens import TokenService

from .conftest import TableRouting, auth, create_group, join_group, send_location, share_location

SEOUL = (37.5665, 126.9780)
SUWON = (37.2636, 127.0286)
INCHEON = (37.4563, 126.7052)
GANGNEUNG = {"name": "강릉역", "lat": 37.7640, "lon": 128.8996}


def group_url(grant: dict, suffix: str = "") -> str:
    return f"/api/v1/groups/{grant['group']['group_id']}{suffix}"


def ready_group(client: TestClient) -> tuple[dict, dict]:
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    assert client.patch(group_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader)).status_code == 200
    share_location(client, leader, *SEOUL)
    share_location(client, member, *SUWON)
    return leader, member


def recommend(client: TestClient, grant: dict) -> dict:
    response = client.post(group_url(grant, "/recommendations"), headers=auth(grant))
    assert response.status_code == 201, response.text
    return response.json()


def select(client: TestClient, grant: dict, result: dict, place_id: str | None = None):
    group = client.get(group_url(grant), headers=auth(grant)).json()
    return client.post(
        group_url(grant, "/meeting-point"),
        json={
            "recommendation_id": result["recommendation_id"],
            "place_id": place_id or result["recommendations"][0]["place_id"],
            "expected_version": group["version"],
        },
        headers=auth(grant),
    )


def test_health_reports_loaded_rest_areas(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["rest_areas"] == 215


def test_create_group_issues_six_character_invite_and_token(client):
    grant = create_group(client)
    assert len(grant["group"]["invite_code"]) == 6
    assert grant["access_token"]
    assert grant["group"]["participants"][0]["role"] == "LEADER"


def test_join_is_case_insensitive_and_adds_member(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"].lower())
    assert member["group"]["group_id"] == leader["group"]["group_id"]
    roles = [p["role"] for p in member["group"]["participants"]]
    assert roles == ["LEADER", "MEMBER"]


def test_joining_does_not_start_location_sharing(client):
    """design.md §9.1: 그룹 참여만으로 위치 공유가 시작되지 않는다."""
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    assert all(p["location_sharing"] is False for p in member["group"]["participants"])
    response = send_location(client, member, *SUWON)
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "CONSENT_REQUIRED"


def test_unknown_invite_code_is_rejected(client):
    response = client.post("/api/v1/groups/join", json={"invite_code": "ZZZZZZ", "nickname": "x"})
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "INVITE_NOT_FOUND"


def test_malformed_invite_code_is_rejected(client):
    assert client.post("/api/v1/groups/join", json={"invite_code": "ABC", "nickname": "x"}).status_code == 422


def test_expired_invite_code_is_rejected(tmp_path):
    settings = Settings(
        _env_file=None,
        database_url=f"sqlite:///{tmp_path / 'expired.db'}",
        token_secret="test-secret-test-secret-test-secret-1234",
        invite_ttl_minutes=-1,
    )
    with TestClient(create_app(settings)) as expired_client:
        leader = create_group(expired_client)
        response = expired_client.post(
            "/api/v1/groups/join",
            json={"invite_code": leader["group"]["invite_code"], "nickname": "x"},
        )
    assert response.status_code == 410
    assert response.json()["error"]["code"] == "INVITE_EXPIRED"


def test_group_capacity_is_limited_to_three(client):
    leader = create_group(client)
    code = leader["group"]["invite_code"]
    join_group(client, code, "두번째")
    join_group(client, code, "세번째")
    response = client.post("/api/v1/groups/join", json={"invite_code": code, "nickname": "네번째"})
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "GROUP_FULL"


def test_concurrent_joins_cannot_exceed_capacity(client):
    leader = create_group(client)
    code = leader["group"]["invite_code"]

    def attempt(index: int) -> int:
        return client.post("/api/v1/groups/join", json={"invite_code": code, "nickname": f"차량{index}"}).status_code

    with ThreadPoolExecutor(max_workers=6) as pool:
        codes = list(pool.map(attempt, range(6)))
    assert codes.count(201) == 2
    assert codes.count(409) == 4


def test_invite_guessing_is_rate_limited(tmp_path):
    settings = Settings(
        _env_file=None,
        database_url=f"sqlite:///{tmp_path / 'limit.db'}",
        token_secret="test-secret-test-secret-test-secret-1234",
        join_attempts_per_minute=3,
    )
    with TestClient(create_app(settings)) as limited:
        codes = [
            limited.post("/api/v1/groups/join", json={"invite_code": "AAAAAA", "nickname": "x"}).status_code
            for _ in range(5)
        ]
    assert codes == [404, 404, 404, 429, 429]


def test_missing_and_tampered_tokens_are_rejected(client):
    grant = create_group(client)
    assert client.get(group_url(grant)).status_code == 401
    bad = client.get(group_url(grant), headers={"Authorization": "Bearer nonsense"})
    assert bad.status_code == 401
    assert bad.json()["error"]["code"] == "TOKEN_INVALID"


def test_expired_token_is_rejected(client, settings):
    grant = create_group(client)
    tokens = TokenService(settings.token_secret, settings.token_ttl_minutes)
    past = datetime.now(timezone.utc) - timedelta(days=2)
    token, _ = tokens.issue(grant["group"]["group_id"], grant["vehicle_id"], past)
    response = client.get(group_url(grant), headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "TOKEN_EXPIRED"


def test_token_of_another_group_is_rejected(client):
    first = create_group(client)
    second = create_group(client, "다른 차량")
    response = client.get(group_url(first), headers=auth(second))
    assert response.status_code == 403


def test_only_leader_changes_destination_and_all_members_see_it(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    denied = client.patch(group_url(member, "/destination"), json=GANGNEUNG, headers=auth(member))
    assert denied.status_code == 403
    assert denied.json()["error"]["code"] == "LEADER_ONLY"
    assert client.patch(group_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader)).status_code == 200
    assert client.get(group_url(member), headers=auth(member)).json()["destination"]["name"] == "강릉역"


def test_meal_purpose_requires_meal_time(client):
    leader = create_group(client)
    response = client.patch(
        group_url(leader, "/meeting-purpose"), json={"meeting_purpose": "MEAL"}, headers=auth(leader)
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "MEAL_TIME_REQUIRED"


def test_invalid_purpose_is_rejected(client):
    leader = create_group(client)
    response = client.patch(
        group_url(leader, "/meeting-purpose"), json={"meeting_purpose": "DANCE"}, headers=auth(leader)
    )
    assert response.status_code == 422


@pytest.mark.parametrize(
    ("body", "code"),
    [
        ({"lat": 37.5, "lon": None}, None),
        ({"lat": 95.0, "lon": 127.0}, None),
        ({"lat": 40.7, "lon": -74.0}, "LOCATION_OUT_OF_SERVICE_AREA"),
    ],
)
def test_invalid_coordinates_are_rejected(client, body, code):
    leader = create_group(client)
    client.patch(group_url(leader, "/location-sharing"), json={"enabled": True}, headers=auth(leader))
    body = {**body, "timestamp": datetime.now(timezone.utc).isoformat()}
    response = client.post(group_url(leader, "/locations"), json=body, headers=auth(leader))
    assert response.status_code == 422
    if code:
        assert response.json()["error"]["code"] == code


def test_timestamp_without_timezone_is_rejected(client):
    leader = create_group(client)
    client.patch(group_url(leader, "/location-sharing"), json={"enabled": True}, headers=auth(leader))
    body = {"lat": SEOUL[0], "lon": SEOUL[1], "timestamp": "2026-11-07T09:00:00"}
    assert client.post(group_url(leader, "/locations"), json=body, headers=auth(leader)).status_code == 422


def test_old_and_future_timestamps_are_rejected(client):
    leader = create_group(client)
    client.patch(group_url(leader, "/location-sharing"), json={"enabled": True}, headers=auth(leader))
    old = send_location(client, leader, *SEOUL, age_seconds=3600)
    assert old.json()["error"]["code"] == "LOCATION_TOO_OLD"
    future = send_location(client, leader, *SEOUL, age_seconds=-3600)
    assert future.json()["error"]["code"] == "LOCATION_FROM_FUTURE"


def test_out_of_order_location_does_not_overwrite_newer_one(client):
    leader = create_group(client)
    share_location(client, leader, *SEOUL)
    late = send_location(client, leader, *SUWON, age_seconds=30)
    assert late.status_code == 202
    assert late.json() == {"accepted": False}
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert (me["lat"], me["lon"]) == SEOUL


def test_location_older_than_twenty_seconds_is_marked_stale(client):
    leader = create_group(client)
    share_location(client, leader, *SEOUL, age_seconds=25)
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["status"] == "STALE"


def test_fresh_location_is_active(client):
    leader = create_group(client)
    share_location(client, leader, *SEOUL)
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["status"] == "ACTIVE"
    assert me["timestamp"].endswith("Z")


def test_withdrawing_consent_erases_location_immediately(client):
    leader = create_group(client)
    share_location(client, leader, *SEOUL)
    response = client.patch(group_url(leader, "/location-sharing"), json={"enabled": False}, headers=auth(leader))
    assert response.json()["status"] == "PAUSED"
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["lat"] is None and me["timestamp"] is None
    assert send_location(client, leader, *SEOUL).status_code == 403


def test_recommendation_requires_destination(client):
    leader = create_group(client)
    response = client.post(group_url(leader, "/recommendations"), headers=auth(leader))
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "DESTINATION_REQUIRED"


def test_recommendation_requires_two_sharing_vehicles(client):
    leader = create_group(client)
    join_group(client, leader["group"]["invite_code"])
    client.patch(group_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader))
    share_location(client, leader, *SEOUL)
    response = client.post(group_url(leader, "/recommendations"), headers=auth(leader))
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "NOT_ENOUGH_VEHICLES"


def test_recommendation_returns_documented_fields(client):
    leader, _ = ready_group(client)
    result = recommend(client, leader)
    assert result["route_source"] == "ESTIMATE"
    assert 1 <= len(result["recommendations"]) <= 3
    item = result["recommendations"][0]
    for key in (
        "place_id", "name", "latitude", "longitude", "direction",
        "estimated_group_meeting_time", "per_vehicle_arrival_eta", "per_vehicle_detour_minutes",
        "total_wait_minutes", "max_detour_minutes", "shared_driving_minutes",
        "parking_or_stop_suitability", "is_destination_fallback", "score", "reason",
    ):
        assert key in item
    assert result["destination_comparison"]["is_destination_fallback"] is True


def test_recommendation_excludes_vehicle_without_consent(client):
    leader, member = ready_group(client)
    third = join_group(client, leader["group"]["invite_code"], "인천 차량")
    result = recommend(client, leader)
    assert result["excluded_vehicles"] == [third["vehicle_id"]]
    assert third["vehicle_id"] not in result["destination_comparison"]["per_vehicle_arrival_eta"]


def test_get_recommendations_before_compute_is_not_found(client):
    leader = create_group(client)
    assert client.get(group_url(leader, "/recommendations"), headers=auth(leader)).status_code == 404


def test_changing_destination_marks_recommendations_stale(client):
    leader, _ = ready_group(client)
    result = recommend(client, leader)
    client.patch(
        group_url(leader, "/destination"),
        json={"name": "속초", "lat": 38.207, "lon": 128.5918},
        headers=auth(leader),
    )
    latest = client.get(group_url(leader, "/recommendations"), headers=auth(leader)).json()
    assert latest["stale"] is True
    response = select(client, leader, result)
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "RECOMMENDATION_OUTDATED"


def test_selecting_meeting_point_syncs_to_group(client):
    leader, member = ready_group(client)
    result = recommend(client, leader)
    response = select(client, leader, result)
    assert response.status_code == 200
    view = client.get(group_url(member), headers=auth(member)).json()
    assert view["selected_meeting_point_id"] == result["recommendations"][0]["place_id"]
    assert view["phase"] == "EN_ROUTE"


def test_destination_can_be_selected_as_meeting_point(client):
    leader, _ = ready_group(client)
    result = recommend(client, leader)
    response = select(client, leader, result, "destination")
    assert response.status_code == 200
    assert response.json()["meeting_point"]["is_destination_fallback"] is True


def test_only_leader_selects_meeting_point(client):
    leader, member = ready_group(client)
    result = recommend(client, leader)
    assert select(client, member, result).status_code == 403


def test_selecting_unknown_place_is_rejected(client):
    leader, _ = ready_group(client)
    assert select(client, leader, recommend(client, leader), "nowhere").status_code == 404


def test_concurrent_selection_only_one_wins(client):
    """design.md §14.2: 추천 장소 선택 동시 요청 충돌."""
    leader, _ = ready_group(client)
    result = recommend(client, leader)
    version = client.get(group_url(leader), headers=auth(leader)).json()["version"]
    body = {
        "recommendation_id": result["recommendation_id"],
        "place_id": result["recommendations"][0]["place_id"],
        "expected_version": version,
    }

    def attempt(_: int) -> int:
        return client.post(group_url(leader, "/meeting-point"), json=body, headers=auth(leader)).status_code

    with ThreadPoolExecutor(max_workers=4) as pool:
        codes = list(pool.map(attempt, range(4)))
    assert codes.count(200) == 1
    assert codes.count(409) == 3


def test_vehicle_within_radius_is_marked_arrived_and_group_completes(client):
    leader, member = ready_group(client)
    point = recommend(client, leader)["recommendations"][0]
    select(client, leader, recommend(client, leader))
    point = client.get(group_url(leader), headers=auth(leader)).json()["meeting_point"]

    send_location(client, leader, point["latitude"], point["longitude"])
    view = client.get(group_url(leader), headers=auth(leader)).json()
    assert [p["arrived"] for p in view["participants"]] == [True, False]
    assert view["phase"] == "EN_ROUTE"

    send_location(client, member, point["latitude"], point["longitude"])
    assert client.get(group_url(leader), headers=auth(leader)).json()["phase"] == "ALL_ARRIVED"


def selected_point(client: TestClient, grant: dict) -> dict:
    return client.get(group_url(grant), headers=auth(grant)).json()["meeting_point"]


def test_navigation_arrival_event_counts_as_arrived(client):
    leader, _ = ready_group(client)
    select(client, leader, recommend(client, leader))
    point = selected_point(client, leader)
    # 도착 반경(300m) 밖이지만 내비가 도착했다고 알린 경우
    send_location(client, leader, point["latitude"] + 0.005, point["longitude"], arrived_by_nav=True)
    view = client.get(group_url(leader), headers=auth(leader)).json()
    assert view["participants"][0]["arrived"] is True


def test_navigation_arrival_far_from_meeting_point_is_ignored(client):
    """운전자의 다른 경유지나 목적지 도착을 합류 도착으로 세지 않는다."""
    leader, _ = ready_group(client)
    select(client, leader, recommend(client, leader))
    send_location(client, leader, SEOUL[0] + 0.001, SEOUL[1], arrived_by_nav=True)
    view = client.get(group_url(leader), headers=auth(leader)).json()
    assert view["participants"][0]["arrived"] is False


def test_navigation_values_of_previous_meeting_point_are_ignored(client):
    """합류 지점이 바뀐 직후 앱이 보낸 이전 지점의 ETA·도착은 믿지 않는다."""
    leader, _ = ready_group(client)
    select(client, leader, recommend(client, leader))
    point = selected_point(client, leader)
    send_location(
        client, leader, point["latitude"] + 0.005, point["longitude"],
        nav_remain_seconds=4321, arrived_by_nav=True, nav_target_id="previous-place",
    )
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["arrived"] is False
    assert me["eta_source"] == "ROAD_ROUTE"


def test_navigation_values_of_current_meeting_point_are_accepted(client):
    leader, _ = ready_group(client)
    select(client, leader, recommend(client, leader))
    point = selected_point(client, leader)
    send_location(client, leader, SEOUL[0] + 0.001, SEOUL[1], nav_remain_seconds=1234, nav_target_id=point["place_id"])
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["eta_seconds"] == 1234
    assert me["eta_source"] == "VEHICLE_NAV"


def test_navigation_eta_is_ignored_before_a_meeting_point_exists(client):
    """대기실에서 내비의 경유지 시간은 그룹 목적지까지의 시간이 아니다."""
    leader, _ = ready_group(client)
    send_location(client, leader, SEOUL[0] + 0.001, SEOUL[1], nav_remain_seconds=60)
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["eta_source"] == "ROAD_ROUTE"


def test_arrived_vehicle_is_not_turned_back_into_delayed_by_next_leg(client):
    """도착 뒤 내비는 다음 구간의 시간을 준다. 합류 지연으로 표시되면 안 된다."""
    leader, member = ready_group(client)
    select(client, leader, recommend(client, leader))
    point = selected_point(client, leader)
    send_location(client, member, point["latitude"], point["longitude"])
    send_location(client, member, point["latitude"], point["longitude"], nav_remain_seconds=20 * 3600)
    view = client.get(group_url(leader), headers=auth(leader)).json()
    arrived = next(p for p in view["participants"] if p["vehicle_id"] == member["vehicle_id"])
    assert arrived["arrived"] is True
    assert arrived["eta_seconds"] == 0
    assert arrived["delayed"] is False
    assert view["reroute_suggested"] is False


def test_choosing_new_meeting_point_clears_previous_eta_and_arrival(client):
    leader, _ = ready_group(client)
    result = recommend(client, leader)
    select(client, leader, result)
    point = selected_point(client, leader)
    send_location(client, leader, point["latitude"], point["longitude"])
    another = next(item for item in result["recommendations"] if item["place_id"] != point["place_id"])
    assert select(client, leader, result, another["place_id"]).status_code == 200
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["arrived"] is False
    assert me["eta_seconds"] is None and me["eta_source"] is None


def test_vehicle_navigation_eta_takes_priority_over_road_estimate(client):
    leader, _ = ready_group(client)
    select(client, leader, recommend(client, leader))
    send_location(client, leader, SEOUL[0] + 0.001, SEOUL[1], nav_remain_seconds=1234)
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["eta_seconds"] == 1234
    assert me["eta_source"] == "VEHICLE_NAV"


def test_road_eta_is_computed_for_selected_meeting_point(client):
    leader, _ = ready_group(client)
    select(client, leader, recommend(client, leader))
    send_location(client, leader, SEOUL[0] + 0.001, SEOUL[1])
    me = client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert me["eta_seconds"] > 0
    assert me["eta_source"] == "ROAD_ROUTE"


def test_large_eta_slip_flags_reroute_without_changing_the_plan(client):
    """design.md §9.2: 지연을 알리기만 하고 합류 지점을 자동으로 바꾸지 않는다."""
    leader, _ = ready_group(client)
    select(client, leader, recommend(client, leader))
    before = client.get(group_url(leader), headers=auth(leader)).json()
    send_location(client, leader, SEOUL[0] + 0.001, SEOUL[1], nav_remain_seconds=20 * 3600)
    after = client.get(group_url(leader), headers=auth(leader)).json()
    assert after["reroute_suggested"] is True
    assert after["selected_meeting_point_id"] == before["selected_meeting_point_id"]


def test_member_leaving_erases_location_and_blocks_access(client):
    leader, member = ready_group(client)
    assert client.post(group_url(member, "/leave"), headers=auth(member)).status_code == 204
    view = client.get(group_url(leader), headers=auth(leader)).json()
    assert [p["vehicle_id"] for p in view["participants"]] == [leader["vehicle_id"]]
    assert client.get(group_url(member), headers=auth(member)).status_code == 403


def test_leader_leaving_hands_over_to_next_member(client):
    leader, member = ready_group(client)
    client.post(group_url(leader, "/leave"), headers=auth(leader))
    view = client.get(group_url(member), headers=auth(member)).json()
    assert view["participants"][0]["role"] == "LEADER"
    assert client.patch(group_url(member, "/destination"), json=GANGNEUNG, headers=auth(member)).status_code == 200


def test_last_member_leaving_closes_session(client):
    leader = create_group(client)
    client.post(group_url(leader, "/leave"), headers=auth(leader))
    response = client.post(
        "/api/v1/groups/join", json={"invite_code": leader["group"]["invite_code"], "nickname": "x"}
    )
    assert response.status_code == 404


def test_remaining_vehicles_complete_when_absent_member_leaves(client):
    leader, member = ready_group(client)
    select(client, leader, recommend(client, leader))
    point = client.get(group_url(leader), headers=auth(leader)).json()["meeting_point"]
    send_location(client, leader, point["latitude"], point["longitude"])
    client.post(group_url(member, "/leave"), headers=auth(member))
    assert client.get(group_url(leader), headers=auth(leader)).json()["phase"] == "ALL_ARRIVED"


def test_completing_session_erases_all_locations(client):
    leader, member = ready_group(client)
    assert client.post(group_url(member, "/complete"), headers=auth(member)).status_code == 403
    assert client.post(group_url(leader, "/complete"), headers=auth(leader)).status_code == 204
    view = client.get(group_url(leader), headers=auth(leader)).json()
    assert view["phase"] == "CLOSED"
    assert all(p["lat"] is None and p["location_sharing"] is False for p in view["participants"])
    assert send_location(client, leader, *SEOUL).status_code == 410


def test_closed_session_rejects_changes(client):
    leader, _ = ready_group(client)
    client.post(group_url(leader, "/complete"), headers=auth(leader))
    response = client.patch(group_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader))
    assert response.status_code == 410
    assert response.json()["error"]["code"] == "SESSION_CLOSED"


def test_routing_failure_returns_last_good_result_not_a_guess(settings):
    routing = TableRouting(seconds_per_degree=3600)
    with TestClient(create_app(settings, routing=routing)) as test_client:
        leader, _ = ready_group(test_client)
        good = recommend(test_client, leader)
        routing.failing = True
        response = test_client.post(group_url(leader, "/recommendations"), headers=auth(leader))
    assert response.status_code == 503
    error = response.json()["error"]
    assert error["code"] == "ROUTING_UNAVAILABLE"
    assert error["last_good"]["recommendation_id"] == good["recommendation_id"]


def test_routing_failure_during_eta_keeps_previous_value(settings):
    routing = TableRouting(seconds_per_degree=3600)
    with TestClient(create_app(settings, routing=routing)) as test_client:
        leader, _ = ready_group(test_client)
        before = test_client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
        routing.failing = True
        response = send_location(test_client, leader, SEOUL[0] + 0.001, SEOUL[1])
        after = test_client.get(group_url(leader), headers=auth(leader)).json()["participants"][0]
    assert response.status_code == 202
    assert after["eta_seconds"] == before["eta_seconds"]


def test_slow_recommendation_reduces_candidate_count(settings):
    settings.recommendation_target_seconds = -1
    settings.candidate_top_k = 10
    with TestClient(create_app(settings)) as test_client:
        leader, _ = ready_group(test_client)
        recommend(test_client, leader)
        service = test_client.app.state.container.recommendations
        assert service._top_k_by_group[leader["group"]["group_id"]] == 7


# ---- 동시 요청 ----


class PausedRouting:
    """ETA 조회를 멈춰 두고 그사이 다른 요청을 끼워 넣는다."""

    def __init__(self, routing):
        self._matrix = routing.matrix
        self.entered = threading.Event()
        self.resume = threading.Event()
        routing.matrix = self

    def __call__(self, *args, **kwargs):
        self.entered.set()
        assert self.resume.wait(10), "시험 동기화 시간 초과"
        return self._matrix(*args, **kwargs)


@pytest.fixture
def eta_client(settings):
    settings.eta_refresh_seconds = 0
    with TestClient(create_app(settings, routing=TableRouting())) as test_client:
        yield test_client


def in_flight_location(client: TestClient, grant: dict, end_session) -> None:
    """외부 경로 조회 중인 위치 전송과 end_session을 겹친다."""
    container = client.app.state.container
    paused = PausedRouting(container.groups._routing)
    group_id, vehicle_id = grant["group"]["group_id"], grant["vehicle_id"]
    fix = LocationUpdate(lat=37.1234, lon=127.1234, timestamp=datetime.now(timezone.utc))
    with ThreadPoolExecutor(max_workers=1) as pool:
        running = pool.submit(container.groups.update_location, group_id, vehicle_id, fix)
        assert paused.entered.wait(10)
        try:
            end_session(container, group_id, vehicle_id)
        finally:
            paused.resume.set()
        running.result(timeout=10)


def own_view(client: TestClient, grant: dict) -> dict:
    view = client.get(group_url(grant), headers=auth(grant)).json()
    return next(p for p in view["participants"] if p["vehicle_id"] == grant["vehicle_id"])


def test_in_flight_location_does_not_restore_after_withdrawal(eta_client):
    leader, _ = ready_group(eta_client)
    in_flight_location(eta_client, leader, lambda c, g, v: c.groups.set_location_sharing(g, v, False))
    me = own_view(eta_client, leader)
    assert me["location_sharing"] is False
    assert me["lat"] is None and me["timestamp"] is None and me["eta_seconds"] is None


def test_in_flight_location_does_not_restore_after_session_closed(eta_client):
    leader, _ = ready_group(eta_client)
    in_flight_location(eta_client, leader, lambda c, g, v: c.groups.complete(g, v))
    view = eta_client.get(group_url(leader), headers=auth(leader)).json()
    assert view["phase"] == "CLOSED"
    assert all(p["lat"] is None for p in view["participants"])


def test_in_flight_location_does_not_restore_after_leaving(eta_client):
    leader, member = ready_group(eta_client)
    in_flight_location(eta_client, member, lambda c, g, v: c.groups.leave(g, v))
    with eta_client.app.state.container.groups._sessions() as db:
        assert db.get(Participant, member["vehicle_id"]).lat is None


def test_first_fix_in_flight_does_not_survive_withdrawal(eta_client):
    """이전 좌표가 없을 때도 철회가 이겨야 한다. ORM 변경 감지에 맡기면 놓치는 경우다."""
    leader = create_group(eta_client)
    eta_client.patch(group_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader))
    eta_client.patch(group_url(leader, "/location-sharing"), json={"enabled": True}, headers=auth(leader))
    in_flight_location(eta_client, leader, lambda c, g, v: c.groups.set_location_sharing(g, v, False))
    assert own_view(eta_client, leader)["lat"] is None


def test_road_eta_for_previous_target_is_dropped(eta_client):
    """조회하는 사이 목적지가 바뀌면 이전 목적지의 ETA를 저장하지 않는다."""
    leader, _ = ready_group(eta_client)

    def change_destination(container, group_id, vehicle_id):
        request = DestinationRequest(name="부산역", lat=35.1152, lon=129.0422)
        container.groups.set_destination(group_id, vehicle_id, request)

    in_flight_location(eta_client, leader, change_destination)
    me = own_view(eta_client, leader)
    assert me["lat"] == 37.1234
    assert me["eta_seconds"] is None


def paused_recommendation(client: TestClient, grant: dict, action) -> object:
    """추천을 계산하는 사이 action을 끼워 넣고 응답을 돌려준다."""
    container = client.app.state.container
    service = container.recommendations
    original = service._engine.recommend

    def recommend_then_act(request, top_k):
        result = original(request, top_k)
        action(container)
        return result

    service._engine.recommend = recommend_then_act
    return client.post(group_url(grant, "/recommendations"), headers=auth(grant))


def test_recommendation_finished_after_destination_change_is_discarded(client):
    leader, _ = ready_group(client)
    group_id, vehicle_id = leader["group"]["group_id"], leader["vehicle_id"]
    busan = DestinationRequest(name="부산역", lat=35.1152, lon=129.0422)
    response = paused_recommendation(
        client, leader, lambda c: c.groups.set_destination(group_id, vehicle_id, busan)
    )
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "RECOMMENDATION_OUTDATED"
    assert client.get(group_url(leader, "/recommendations"), headers=auth(leader)).status_code == 404


def test_recommendation_finished_after_session_closed_is_discarded(client):
    leader, _ = ready_group(client)
    group_id, vehicle_id = leader["group"]["group_id"], leader["vehicle_id"]
    response = paused_recommendation(client, leader, lambda c: c.groups.complete(group_id, vehicle_id))
    assert response.status_code == 409


def test_plan_change_right_after_saving_invalidates_the_saved_result(client):
    leader, _ = ready_group(client)
    result = recommend(client, leader)
    client.patch(
        group_url(leader, "/meeting-purpose"), json={"meeting_purpose": "REST"}, headers=auth(leader)
    )
    assert select(client, leader, result).status_code == 409


# ---- 차량 구성 변화 ----


def test_recommendation_without_new_vehicle_cannot_be_selected(client):
    leader, _ = ready_group(client)
    result = recommend(client, leader)
    newcomer = join_group(client, leader["group"]["invite_code"], "인천 차량")
    share_location(client, newcomer, *INCHEON)
    response = select(client, leader, result)
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "RECOMMENDATION_OUTDATED"
    latest = client.get(group_url(leader, "/recommendations"), headers=auth(leader)).json()
    assert latest["stale"] is True


def test_recommendation_with_departed_vehicle_cannot_be_selected(client):
    leader, member = ready_group(client)
    result = recommend(client, leader)
    client.post(group_url(member, "/leave"), headers=auth(member))
    assert select(client, leader, result).status_code == 409


def test_recommendation_with_withdrawn_vehicle_cannot_be_selected(client):
    leader, member = ready_group(client)
    result = recommend(client, leader)
    client.patch(group_url(member, "/location-sharing"), json={"enabled": False}, headers=auth(member))
    assert select(client, leader, result).status_code == 409


def test_vehicle_without_location_does_not_block_selection(client):
    """위치가 없어 계산에서 빠진 차량은 다시 계산해도 그대로이므로 선택을 막지 않는다."""
    leader, _ = ready_group(client)
    third = join_group(client, leader["group"]["invite_code"], "인천 차량")
    client.patch(group_url(third, "/location-sharing"), json={"enabled": True}, headers=auth(third))
    result = recommend(client, leader)
    assert result["excluded_vehicles"] == [third["vehicle_id"]]
    assert select(client, leader, result).status_code == 200


# ---- 출발 시각 ----


def departed_at(client: TestClient, grant: dict):
    with client.app.state.container.groups._sessions() as db:
        return db.get(Group, grant["group"]["group_id"]).departed_at


def test_lobby_recommendation_does_not_start_the_trip_clock(client):
    leader, _ = ready_group(client)
    recommend(client, leader)
    assert departed_at(client, leader) is None


def test_trip_clock_starts_at_first_selection_and_survives_replanning(client):
    leader, _ = ready_group(client)
    select(client, leader, recommend(client, leader))
    started = departed_at(client, leader)
    assert started is not None
    client.patch(
        group_url(leader, "/destination"),
        json={"name": "속초", "lat": 38.207, "lon": 128.5918},
        headers=auth(leader),
    )
    assert departed_at(client, leader) == started


def test_default_settings_offer_a_rest_area_without_external_routing(tmp_path):
    """기본 설정(estimate)에서도 경유지 등록 흐름을 시험할 수 있어야 한다."""
    settings = Settings(
        _env_file=None,
        database_url=f"sqlite:///{tmp_path / 'default.db'}",
        token_secret="test-secret-test-secret-test-secret-1234",
    )
    with TestClient(create_app(settings)) as default_client:
        leader, _ = ready_group(default_client)
        result = recommend(default_client, leader)
    assert any(not item["is_destination_fallback"] for item in result["recommendations"])
