"""목적지 없는 그룹 드라이브와 비공개 목적지."""

from __future__ import annotations

import random
from datetime import datetime, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.integrations.drive_spots import DriveSpot, DriveTheme, load_drive_spots
from app.main import create_app
from app.optimization.types import LatLon
from app.services import free_drive

from .conftest import TableRouting, auth, create_group, join_group, send_location, share_location

LAT = 37.5665
LEADER = (LAT, 126.978)
MEMBER = (LAT, 127.078)
REST_AREA_LON = 127.478

# TableRouting은 경도 1도를 1시간으로 계산한다. 리더 위치에서 편도 30분이면 왕복 1시간이다.
SPOTS = [
    ("sea-1h", "바다 1시간", "SEA", 127.478),
    ("sea-1h-b", "바다 1시간 B", "SEA", 127.528),
    ("forest-2h", "숲 2시간", "FOREST", 127.978),
    ("night-3h", "야경 3시간", "NIGHT", 128.478),
]
REST_AREA_HEADER = (
    "id,name,route_name,route_no,direction,bidirectional,toward,toward_lat,toward_lon,lat,lon,kind,"
    "open_start,open_end,parking_spaces,has_restaurant,has_ev_charger,has_gas_station,has_toilet,has_store,"
    "phone,data_date"
)


def spot(spot_id: str, themes: str, lon: float, lat: float = LAT) -> DriveSpot:
    return DriveSpot(
        spot_id, spot_id, frozenset(DriveTheme(t) for t in themes.split("|")), "시험", LatLon(lat, lon), "설명"
    )


@pytest.fixture
def drive_settings(tmp_path: Path) -> Settings:
    spots = tmp_path / "spots.csv"
    lines = ["id,name,themes,region,lat,lon,description,source"]
    lines += [f"{sid},{name},{themes},시험 지역,{LAT},{lon},{name} 설명,OSM" for sid, name, themes, lon in SPOTS]
    spots.write_text("\n".join(lines), encoding="utf-8")
    areas = tmp_path / "areas.csv"
    areas.write_text(
        REST_AREA_HEADER
        + f"\nra-mid,중간휴게소,시험선,1,양방향,Y,,,,{LAT},{REST_AREA_LON},GENERAL,00:00,23:59,200,Y,Y,Y,Y,Y,,2026-09-17",
        encoding="utf-8",
    )
    return Settings(
        _env_file=None,
        database_url=f"sqlite:///{tmp_path / 'drive.db'}",
        token_secret="test-secret-test-secret-test-secret-1234",
        routing_provider="estimate",
        candidate_buffer_km=15,
        join_attempts_per_minute=1000,
        drive_spots_csv=spots,
        rest_areas_csv=areas,
    )


@pytest.fixture
def drive_client(drive_settings: Settings):
    app = create_app(drive_settings, routing=TableRouting(), rng=random.Random(7))
    with TestClient(app) as test_client:
        yield test_client


def url(grant: dict, suffix: str = "") -> str:
    return f"/api/v1/groups/{grant['group']['group_id']}{suffix}"


def start(client: TestClient, grant: dict, **body):
    payload = {"hours": 1, "theme": "SEA", "private": False, **body}
    return client.post(url(grant, "/free-drive"), json=payload, headers=auth(grant))


def snapshot(client: TestClient, grant: dict) -> dict:
    return client.get(url(grant), headers=auth(grant)).json()


def receive_type(socket, event_type: str, limit: int = 12) -> dict:
    for _ in range(limit):
        event = socket.receive_json()
        if event["type"] == event_type:
            return event
    raise AssertionError(f"{event_type} 이벤트를 받지 못했습니다.")


# ---- 코스 고르기 ----


def minutes_matrix(*one_way_minutes: float | None) -> list[list[float | None]]:
    return [[None if m is None else m * 60 for m in one_way_minutes]]


def test_choose_only_returns_spots_within_tolerance():
    spots = [spot("a", "SEA", 0), spot("b", "SEA", 0), spot("c", "SEA", 0)]
    durations = minutes_matrix(20, 55, 65)  # 왕복 40, 110, 130분
    for seed in range(20):
        pick = free_drive.choose(spots, ["v"], durations, 120, 15, [], random.Random(seed))
        assert pick.spot.id in {"b", "c"}
        assert pick.within_tolerance
        assert pick.in_window == 2


def test_choose_averages_round_trip_over_vehicles():
    spots = [spot("a", "SEA", 0)]
    durations = [[30 * 60], [90 * 60]]
    pick = free_drive.choose(spots, ["v1", "v2"], durations, 120, 15, [], random.Random(1))
    assert pick.round_trip_minutes == pytest.approx(120)
    assert pick.one_way_minutes == {"v1": 30, "v2": 90}


def test_reroll_skips_spots_already_shown():
    spots = [spot("a", "SEA", 0), spot("b", "SEA", 0)]
    durations = minutes_matrix(60, 60)
    for seed in range(10):
        pick = free_drive.choose(spots, ["v"], durations, 120, 15, ["a"], random.Random(seed))
        assert pick.spot.id == "b"


def test_reroll_after_every_spot_was_shown_avoids_the_last_one():
    spots = [spot("a", "SEA", 0), spot("b", "SEA", 0)]
    durations = minutes_matrix(60, 60)
    for seed in range(10):
        pick = free_drive.choose(spots, ["v"], durations, 120, 15, ["a", "b"], random.Random(seed))
        assert pick.spot.id == "a"


def test_reroll_after_the_window_is_used_up_moves_to_the_next_closest_spot():
    spots = [spot("only", "SEA", 0), spot("near", "SEA", 0), spot("far", "SEA", 0)]
    durations = minutes_matrix(60, 72, 100)  # 왕복 120, 144, 200분
    pick = free_drive.choose(spots, ["v"], durations, 120, 15, ["only"], random.Random(1))
    assert pick.spot.id == "near"
    assert not pick.within_tolerance and pick.in_window == 1
    assert "모두 보여 드려" in free_drive.drive_notice(pick, DriveTheme.SEA, 2, datetime.now(timezone.utc))


def test_without_a_spot_in_window_the_closest_one_is_marked_out_of_tolerance():
    spots = [spot("near", "SEA", 0), spot("far", "SEA", 0)]
    durations = minutes_matrix(20, 100)  # 왕복 40분, 200분
    pick = free_drive.choose(spots, ["v"], durations, 120, 15, [], random.Random(1))
    assert pick.spot.id == "far"
    assert not pick.within_tolerance
    assert "가장 가까운 곳" in free_drive.drive_notice(pick, DriveTheme.SEA, 2, datetime.now(timezone.utc))


def test_unreachable_spots_are_skipped():
    spots = [spot("island", "SEA", 0), spot("road", "SEA", 0)]
    pick = free_drive.choose(spots, ["v"], minutes_matrix(None, 60), 120, 15, [], random.Random(1))
    assert pick.spot.id == "road"
    assert free_drive.choose(spots[:1], ["v"], minutes_matrix(None), 120, 15, [], random.Random(1)) is None


def test_prefilter_keeps_the_theme_and_random_keeps_everything():
    spots = [spot("sea", "SEA", 127.5), spot("forest", "FOREST", 127.5), spot("both", "FOREST|NIGHT", 127.5)]
    origin = [LatLon(LAT, 127.0)]
    assert {s.id for s in free_drive.prefilter(spots, DriveTheme.FOREST, origin, 60)} == {"forest", "both"}
    assert {s.id for s in free_drive.prefilter(spots, DriveTheme.RANDOM, origin, 60)} == {"sea", "forest", "both"}


def test_prefilter_orders_by_estimated_time_and_limits_the_count():
    spots = [spot(f"s{i}", "SEA", 127.0 + i * 0.2) for i in range(10)]
    chosen = free_drive.prefilter(spots, DriveTheme.SEA, [LatLon(LAT, 127.0)], 120, limit=3)
    assert len(chosen) == 3
    estimates = [free_drive.estimated_round_trip_minutes(LatLon(LAT, 127.0), s) for s in chosen]
    assert max(abs(e - 120) for e in estimates) <= min(
        abs(free_drive.estimated_round_trip_minutes(LatLon(LAT, 127.0), s) - 120) for s in spots if s not in chosen
    )


def test_night_course_warns_when_arriving_before_sunset():
    pick = free_drive.DrivePick(spot("n", "NIGHT", 0), 60, {"v": 30}, True, 1)
    afternoon = datetime(2026, 11, 8, 5, 0, tzinfo=timezone.utc)  # 14:00 KST, 도착 14:30
    evening = datetime(2026, 11, 8, 10, 0, tzinfo=timezone.utc)  # 19:00 KST
    assert "해가 진 뒤" in free_drive.drive_notice(pick, DriveTheme.NIGHT, 1, afternoon)
    assert free_drive.drive_notice(pick, DriveTheme.NIGHT, 1, evening) is None


def test_bundled_spot_list_loads_and_covers_every_theme():
    spots = load_drive_spots(Settings(_env_file=None).drive_spots_csv)
    assert len(spots) >= 80
    for theme in (DriveTheme.SEA, DriveTheme.FOREST, DriveTheme.NIGHT, DriveTheme.LAKE):
        assert sum(theme in s.themes for s in spots) >= 15
    assert len({s.id for s in spots}) == len(spots)
    assert all(33 <= s.position.lat <= 39 and 124 <= s.position.lon <= 132 for s in spots)


# ---- API ----


def test_free_drive_sets_a_destination_within_the_chosen_time(drive_client):
    leader = create_group(drive_client)
    share_location(drive_client, leader, *LEADER)
    response = start(drive_client, leader, hours=1, theme="SEA")
    assert response.status_code == 200, response.text
    group = response.json()
    assert group["destination"]["name"] in {"바다 1시간", "바다 1시간 B"}
    info = group["free_drive"]
    assert info["hours"] == 1 and info["theme"] == "SEA" and info["theme_label"] == "바다"
    assert abs(info["round_trip_minutes"] - 60) <= 15
    assert info["within_tolerance"] is True
    assert info["private"] is False and info["revealed"] is True
    assert info["description"].endswith("설명")


def test_free_drive_theme_picks_matching_spot(drive_client):
    leader = create_group(drive_client)
    share_location(drive_client, leader, *LEADER)
    group = start(drive_client, leader, hours=3, theme="NIGHT").json()
    assert group["destination"]["name"] == "야경 3시간"


def test_free_drive_outside_tolerance_explains_the_choice(drive_client):
    leader = create_group(drive_client)
    share_location(drive_client, leader, *LEADER)
    group = start(drive_client, leader, hours=5, theme="FOREST").json()
    assert group["destination"]["name"] == "숲 2시간"
    assert group["free_drive"]["within_tolerance"] is False
    assert "가장 가까운 곳" in group["free_drive"]["notice"]


def test_reroll_offers_a_different_spot(drive_client):
    leader = create_group(drive_client)
    share_location(drive_client, leader, *LEADER)
    first = start(drive_client, leader).json()
    second = start(drive_client, leader, reroll=True).json()
    assert {first["destination"]["name"], second["destination"]["name"]} == {"바다 1시간", "바다 1시간 B"}
    # 비공개일 때도 그룹원 화면이 새 코스로 바뀐 것을 알 수 있어야 한다.
    assert first["free_drive"]["plan_id"] != second["free_drive"]["plan_id"]


def test_free_drive_is_leader_only(drive_client):
    leader = create_group(drive_client)
    member = join_group(drive_client, leader["group"]["invite_code"])
    share_location(drive_client, member, *MEMBER)
    assert start(drive_client, member).json()["error"]["code"] == "LEADER_ONLY"


def test_free_drive_needs_a_shared_location(drive_client):
    leader = create_group(drive_client)
    response = start(drive_client, leader)
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "LOCATION_REQUIRED"


@pytest.mark.parametrize("hours", [0, 6])
def test_hours_outside_one_to_five_are_rejected(drive_client, hours):
    leader = create_group(drive_client)
    share_location(drive_client, leader, *LEADER)
    assert start(drive_client, leader, hours=hours).status_code == 422


def test_setting_a_destination_directly_ends_the_free_drive(drive_client):
    leader = create_group(drive_client)
    share_location(drive_client, leader, *LEADER)
    start(drive_client, leader, private=True)
    group = drive_client.patch(
        url(leader, "/destination"), json={"name": "강릉역", "lat": 37.764, "lon": 128.8996}, headers=auth(leader)
    ).json()
    assert group["free_drive"] is None
    member = join_group(drive_client, leader["group"]["invite_code"])
    assert member["group"]["destination"]["name"] == "강릉역"


# ---- 비공개 목적지 ----


def private_group(client: TestClient) -> tuple[dict, dict, str]:
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    share_location(client, leader, *LEADER)
    share_location(client, member, *MEMBER)
    group = start(client, leader, hours=2, theme="FOREST", private=True).json()
    return leader, member, group["destination"]["name"]


def test_private_destination_is_visible_only_to_the_leader(drive_client):
    leader, member, name = private_group(drive_client)
    assert name == "숲 2시간"
    mine = snapshot(drive_client, leader)
    theirs = snapshot(drive_client, member)
    assert mine["destination"]["name"] == name and mine["destination_hidden"] is False
    assert theirs["destination"] is None and theirs["destination_hidden"] is True
    # 테마와 시간은 함께 알아야 준비할 수 있다. 지역과 설명은 목적지를 짐작하게 하므로 가린다.
    assert theirs["free_drive"]["theme_label"] == "숲길"
    assert theirs["free_drive"]["round_trip_minutes"] == mine["free_drive"]["round_trip_minutes"]
    assert theirs["free_drive"]["region"] is None and theirs["free_drive"]["description"] is None
    assert theirs["free_drive"]["private"] is True and theirs["free_drive"]["revealed"] is False


def test_member_joining_later_does_not_see_private_destination(drive_client):
    leader = create_group(drive_client)
    share_location(drive_client, leader, *LEADER)
    start(drive_client, leader, private=True)
    member = join_group(drive_client, leader["group"]["invite_code"])
    assert member["group"]["destination"] is None
    assert member["group"]["destination_hidden"] is True


def test_private_destination_is_masked_per_recipient_on_the_channel(drive_client):
    leader = create_group(drive_client)
    member = join_group(drive_client, leader["group"]["invite_code"])
    share_location(drive_client, leader, *LEADER)
    with drive_client.websocket_connect(url(leader).replace("/api/v1", "/ws"), headers=auth(leader)) as lead_ws, \
            drive_client.websocket_connect(url(member).replace("/api/v1", "/ws"), headers=auth(member)) as member_ws:
        assert start(drive_client, leader, private=True).status_code == 200
        to_leader = receive_type(lead_ws, "group.updated")["payload"]["group"]
        to_member = receive_type(member_ws, "group.updated")["payload"]["group"]
    assert to_leader["destination"] is not None
    assert to_member["destination"] is None and to_member["destination_hidden"] is True


def test_private_recommendations_hide_the_destination_from_members(drive_client):
    leader, member, name = private_group(drive_client)
    result = drive_client.post(url(member, "/recommendations"), headers=auth(member)).json()
    hidden = result["destination_comparison"]
    assert hidden["name"] == "비공개 목적지" and hidden["latitude"] is None and hidden["longitude"] is None
    assert all(item["name"] != name for item in result["recommendations"])
    # 계획 값은 그대로라 언제 만나는지는 알 수 있다.
    assert hidden["per_vehicle_arrival_eta"]
    full = drive_client.get(url(leader, "/recommendations"), headers=auth(leader)).json()
    assert full["destination_comparison"]["name"] == name


def select_place(client: TestClient, leader: dict, place_id: str) -> dict:
    result = client.get(url(leader, "/recommendations"), headers=auth(leader)).json()
    version = snapshot(client, leader)["version"]
    response = client.post(
        url(leader, "/meeting-point"),
        json={"recommendation_id": result["recommendation_id"], "place_id": place_id, "expected_version": version},
        headers=auth(leader),
    )
    assert response.status_code == 200, response.text
    return response.json()


def test_destination_is_revealed_to_everyone_when_all_arrive_at_the_meeting_point(drive_client):
    leader, member, name = private_group(drive_client)
    result = drive_client.post(url(leader, "/recommendations"), headers=auth(leader)).json()
    assert "ra-mid" in {item["place_id"] for item in result["recommendations"]}
    select_place(drive_client, leader, "ra-mid")
    en_route = snapshot(drive_client, member)
    assert en_route["meeting_point"]["name"] == "중간휴게소"
    assert en_route["destination"] is None

    with drive_client.websocket_connect(url(member).replace("/api/v1", "/ws"), headers=auth(member)) as member_ws:
        send_location(drive_client, leader, LAT, REST_AREA_LON)
        send_location(drive_client, member, LAT, REST_AREA_LON)
        revealed = receive_type(member_ws, "group.updated", limit=20)["payload"]
    assert revealed["destination_revealed"] is True
    assert revealed["group"]["phase"] == "ALL_ARRIVED"
    assert revealed["group"]["destination"]["name"] == name
    after = snapshot(drive_client, member)
    assert after["destination"]["name"] == name and after["free_drive"]["revealed"] is True


def test_meeting_at_the_private_destination_reveals_it(drive_client):
    leader, member, name = private_group(drive_client)
    drive_client.post(url(leader, "/recommendations"), headers=auth(leader))
    select_place(drive_client, leader, "destination")
    group = snapshot(drive_client, member)
    assert group["destination"]["name"] == name
    assert group["meeting_point"]["name"] == name


def test_new_leader_learns_the_private_destination(drive_client):
    leader, member, name = private_group(drive_client)
    assert drive_client.post(url(leader, "/leave"), headers=auth(leader)).status_code == 204
    assert snapshot(drive_client, member)["destination"]["name"] == name


def test_public_free_drive_is_visible_to_everyone(drive_client):
    leader = create_group(drive_client)
    member = join_group(drive_client, leader["group"]["invite_code"])
    share_location(drive_client, leader, *LEADER)
    start(drive_client, leader, private=False)
    group = snapshot(drive_client, member)
    assert group["destination"] is not None and group["destination_hidden"] is False
