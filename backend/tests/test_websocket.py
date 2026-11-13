"""design.md §14.1 WebSocket: 동시 접속, 순서 변경, 재접속, 만료 토큰, 참가자 이탈."""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone

import pytest
from starlette.websockets import WebSocket, WebSocketDisconnect, WebSocketState

from app.services.tokens import TokenService
from app.ws.events import Event
from app.ws.manager import ConnectionManager

from .conftest import auth, create_group, join_group, send_location, share_location

SEOUL = (37.5665, 126.9780)
SUWON = (37.2636, 127.0286)
GANGNEUNG = {"name": "강릉역", "lat": 37.7640, "lon": 128.8996}


def ws_url(grant: dict) -> str:
    return f"/ws/groups/{grant['group']['group_id']}"


def rest_url(grant: dict, suffix: str = "") -> str:
    return f"/api/v1/groups/{grant['group']['group_id']}{suffix}"


def receive_until(socket, event_type: str, vehicle_id: str | None = None, limit: int = 10) -> dict:
    for _ in range(limit):
        event = socket.receive_json()
        matches_vehicle = vehicle_id is None or event["payload"].get("vehicle_id") == vehicle_id
        if event["type"] == event_type and matches_vehicle:
            return event
    raise AssertionError(f"{event_type} 이벤트를 받지 못했습니다.")


def location_message(lat: float, lon: float, *, age_seconds: float = 0) -> dict:
    timestamp = datetime.now(timezone.utc) - timedelta(seconds=age_seconds)
    return {"type": "location.updated", "payload": {"lat": lat, "lon": lon, "timestamp": timestamp.isoformat()}}


def test_connection_without_token_is_refused(client):
    grant = create_group(client)
    with client.websocket_connect(ws_url(grant)) as socket:
        event = socket.receive_json()
        assert event["type"] == "error"
        assert event["payload"]["code"] == "TOKEN_MISSING"
        with pytest.raises(WebSocketDisconnect) as closed:
            socket.receive_json()
    assert closed.value.code == 4401


def test_expired_token_is_refused(client, settings):
    grant = create_group(client)
    tokens = TokenService(settings.token_secret, settings.token_ttl_minutes)
    past = datetime.now(timezone.utc) - timedelta(days=2)
    token, _ = tokens.issue(grant["group"]["group_id"], grant["vehicle_id"], past)
    with client.websocket_connect(ws_url(grant), headers={"Authorization": f"Bearer {token}"}) as socket:
        assert socket.receive_json()["payload"]["code"] == "TOKEN_EXPIRED"


def test_token_of_another_group_is_refused(client):
    first = create_group(client)
    second = create_group(client, "다른 차량")
    with client.websocket_connect(ws_url(first), headers=auth(second)) as socket:
        assert socket.receive_json()["payload"]["code"] == "NOT_A_MEMBER"


def test_event_has_common_fields(client):
    grant = create_group(client)
    with client.websocket_connect(ws_url(grant), headers=auth(grant)) as socket:
        event = receive_until(socket, "participant.updated")
    assert set(event) == {"type", "group_id", "timestamp", "payload", "event_id"}
    assert event["group_id"] == grant["group"]["group_id"]


def test_location_is_broadcast_to_other_vehicles(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    share_location(client, member, *SUWON)
    with client.websocket_connect(ws_url(leader), headers=auth(leader)) as watcher:
        with client.websocket_connect(ws_url(member), headers=auth(member)) as sender:
            sender.send_json(location_message(37.3, 127.05))
            event = receive_until(watcher, "participant.updated", member["vehicle_id"])
            while event["payload"]["lat"] != 37.3:
                event = receive_until(watcher, "participant.updated", member["vehicle_id"])
    assert event["payload"]["lon"] == 127.05
    assert event["payload"]["status"] == "ACTIVE"


def test_three_vehicles_receive_each_others_updates(client):
    leader = create_group(client)
    second = join_group(client, leader["group"]["invite_code"], "둘째")
    third = join_group(client, leader["group"]["invite_code"], "셋째")
    for grant, position in ((leader, SEOUL), (second, SUWON), (third, (37.45, 126.7))):
        share_location(client, grant, *position)
    with (
        client.websocket_connect(ws_url(leader), headers=auth(leader)) as a,
        client.websocket_connect(ws_url(second), headers=auth(second)) as b,
        client.websocket_connect(ws_url(third), headers=auth(third)) as c,
    ):
        c.send_json(location_message(37.46, 126.71))
        for socket in (a, b):
            event = receive_until(socket, "participant.updated", third["vehicle_id"])
            while event["payload"]["lat"] != 37.46:
                event = receive_until(socket, "participant.updated", third["vehicle_id"])


def test_out_of_order_update_is_not_broadcast(client):
    leader = create_group(client)
    share_location(client, leader, *SEOUL)
    with client.websocket_connect(ws_url(leader), headers=auth(leader)) as socket:
        receive_until(socket, "participant.updated")
        socket.send_json(location_message(37.2, 127.2, age_seconds=60))
        socket.send_json(location_message(37.1, 127.3))
        event = receive_until(socket, "participant.updated")
    assert event["payload"]["lat"] == 37.1


def test_reconnecting_replaces_previous_connection(client):
    grant = create_group(client)
    with client.websocket_connect(ws_url(grant), headers=auth(grant)) as first:
        receive_until(first, "participant.updated")
        with client.websocket_connect(ws_url(grant), headers=auth(grant)) as second:
            with pytest.raises(WebSocketDisconnect) as closed:
                first.receive_json()
            assert closed.value.code == 4409
            event = receive_until(second, "participant.updated")
            assert event["payload"]["online"] is True


def test_presence_is_reported_when_vehicle_disconnects(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    with client.websocket_connect(ws_url(leader), headers=auth(leader)) as watcher:
        receive_until(watcher, "participant.updated", leader["vehicle_id"])
        with client.websocket_connect(ws_url(member), headers=auth(member)):
            online = receive_until(watcher, "participant.updated", member["vehicle_id"])
            assert online["payload"]["online"] is True
        offline = receive_until(watcher, "participant.updated", member["vehicle_id"])
    assert offline["payload"]["online"] is False


def test_group_snapshot_resyncs_after_reconnect(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    client.patch(rest_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader))
    view = client.get(rest_url(member), headers=auth(member)).json()
    assert view["destination"]["name"] == "강릉역"


def test_destination_change_reaches_members(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    with client.websocket_connect(ws_url(member), headers=auth(member)) as socket:
        client.patch(rest_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader))
        event = receive_until(socket, "group.updated")
    assert event["payload"]["group"]["destination"]["name"] == "강릉역"


def test_recommendations_and_selection_are_pushed_to_members(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    client.patch(rest_url(leader, "/destination"), json=GANGNEUNG, headers=auth(leader))
    share_location(client, leader, *SEOUL)
    share_location(client, member, *SUWON)
    with client.websocket_connect(ws_url(member), headers=auth(member)) as socket:
        result = client.post(rest_url(leader, "/recommendations"), headers=auth(leader)).json()
        ready = receive_until(socket, "recommendations.ready")
        assert ready["payload"]["recommendation_id"] == result["recommendation_id"]

        version = client.get(rest_url(leader), headers=auth(leader)).json()["version"]
        client.post(
            rest_url(leader, "/meeting-point"),
            json={
                "recommendation_id": result["recommendation_id"],
                "place_id": result["recommendations"][0]["place_id"],
                "expected_version": version,
            },
            headers=auth(leader),
        )
        selected = receive_until(socket, "meeting_point.selected")
    assert selected["payload"]["group"]["phase"] == "EN_ROUTE"


def test_leaving_stops_delivery_to_that_vehicle(client):
    """design.md §9.1: 탈퇴하면 다른 참여자의 위치를 더 이상 수신하지 못한다."""
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    with client.websocket_connect(ws_url(member), headers=auth(member)) as socket:
        client.post(rest_url(member, "/leave"), headers=auth(member))
        with pytest.raises(WebSocketDisconnect) as closed:
            for _ in range(5):
                socket.receive_json()
    assert closed.value.code == 4403


def test_remaining_vehicles_keep_working_after_one_leaves(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    share_location(client, leader, *SEOUL)
    with client.websocket_connect(ws_url(leader), headers=auth(leader)) as socket:
        client.post(rest_url(member, "/leave"), headers=auth(member))
        left = receive_until(socket, "participant.updated", member["vehicle_id"])
        assert left["payload"]["status"] == "LEFT"
        socket.send_json(location_message(37.5, 127.1))
        event = receive_until(socket, "participant.updated", leader["vehicle_id"])
    assert event["payload"]["status"] == "ACTIVE"


def test_completing_session_closes_every_connection(client):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    with client.websocket_connect(ws_url(member), headers=auth(member)) as socket:
        client.post(rest_url(leader, "/complete"), headers=auth(leader))
        event = receive_until(socket, "session.closed")
        assert event["payload"]["reason"] == "COMPLETED"
        with pytest.raises(WebSocketDisconnect) as closed:
            socket.receive_json()
    assert closed.value.code == 4410


def test_location_without_consent_gets_error_event(client):
    grant = create_group(client)
    with client.websocket_connect(ws_url(grant), headers=auth(grant)) as socket:
        socket.send_json(location_message(*SEOUL))
        event = receive_until(socket, "error")
    assert event["payload"]["code"] == "CONSENT_REQUIRED"


def test_malformed_payload_gets_error_event_and_keeps_connection(client):
    grant = create_group(client)
    share_location(client, grant, *SEOUL)
    with client.websocket_connect(ws_url(grant), headers=auth(grant)) as socket:
        socket.send_json({"type": "location.updated", "payload": {"lat": "not-a-number"}})
        assert receive_until(socket, "error")["payload"]["code"] == "INVALID_PAYLOAD"
        socket.send_json({"type": "something.else"})
        assert receive_until(socket, "error")["payload"]["code"] == "UNKNOWN_EVENT"
        socket.send_json({"type": "presence.heartbeat"})
        socket.send_json(location_message(37.2, 127.0))
        event = receive_until(socket, "participant.updated")
    assert event["payload"]["lat"] == 37.2


def test_eta_request_returns_updated_participant(client):
    grant = create_group(client)
    client.patch(rest_url(grant, "/destination"), json=GANGNEUNG, headers=auth(grant))
    share_location(client, grant, *SEOUL)
    with client.websocket_connect(ws_url(grant), headers=auth(grant)) as socket:
        socket.send_json({"type": "eta.requested"})
        event = receive_until(socket, "participant.updated")
        while event["payload"]["eta_seconds"] is None:
            event = receive_until(socket, "participant.updated")
    assert event["payload"]["eta_seconds"] > 0


def test_rest_location_fallback_also_broadcasts(client):
    grant = create_group(client)
    share_location(client, grant, *SEOUL)
    with client.websocket_connect(ws_url(grant), headers=auth(grant)) as socket:
        receive_until(socket, "participant.updated")
        send_location(client, grant, 37.4, 127.0)
        event = receive_until(socket, "participant.updated")
    assert event["payload"]["lat"] == 37.4


def test_receive_only_socket_is_closed_when_token_expires(client, settings):
    """메시지를 보내지 않는 연결도 만료 시각 뒤에는 다른 차량의 좌표를 받지 못한다."""
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    tokens = TokenService(settings.token_secret, settings.token_ttl_minutes)
    tokens._ttl = timedelta(seconds=1)
    token, _ = tokens.issue(leader["group"]["group_id"], leader["vehicle_id"], datetime.now(timezone.utc))
    with client.websocket_connect(ws_url(leader), headers={"Authorization": f"Bearer {token}"}) as socket:
        receive_until(socket, "participant.updated")
        expired = receive_until(socket, "error")
        assert expired["payload"]["code"] == "TOKEN_EXPIRED"
        share_location(client, member, 37.3, 127.1)
        with pytest.raises(WebSocketDisconnect) as closed:
            for _ in range(5):
                assert socket.receive_json()["payload"].get("lat") is None
    assert closed.value.code == 4401


def test_remaining_vehicles_see_expired_vehicle_go_offline(client, settings):
    leader = create_group(client)
    member = join_group(client, leader["group"]["invite_code"])
    tokens = TokenService(settings.token_secret, settings.token_ttl_minutes)
    tokens._ttl = timedelta(seconds=1)
    token, _ = tokens.issue(member["group"]["group_id"], member["vehicle_id"], datetime.now(timezone.utc))
    with client.websocket_connect(ws_url(leader), headers=auth(leader)) as watcher:
        receive_until(watcher, "participant.updated", leader["vehicle_id"])
        with client.websocket_connect(ws_url(member), headers={"Authorization": f"Bearer {token}"}):
            assert receive_until(watcher, "participant.updated", member["vehicle_id"])["payload"]["online"] is True
            offline = receive_until(watcher, "participant.updated", member["vehicle_id"])
    assert offline["payload"]["online"] is False


def fake_socket(send) -> WebSocket:
    async def receive():
        await asyncio.sleep(3600)

    socket = WebSocket({"type": "websocket", "path": "/", "headers": [], "query_string": b""}, receive, send)
    socket.client_state = WebSocketState.CONNECTED
    socket.application_state = WebSocketState.CONNECTED
    return socket


def test_dead_socket_does_not_stop_delivery_to_other_vehicles():
    """Starlette는 끊긴 소켓의 OSError를 WebSocketDisconnect로 바꿔 던진다."""
    delivered = []

    async def dead_send(_message):
        raise OSError("client gone")

    async def live_send(message):
        delivered.append(message)

    async def scenario():
        manager = ConnectionManager()
        manager._groups["g"]["dead"] = fake_socket(dead_send)
        manager._groups["g"]["live"] = fake_socket(live_send)
        await manager.publish([Event("participant.updated", "g", {"lat": 37.5})])
        await manager.close_group("g")

    asyncio.run(scenario())
    assert [message["type"] for message in delivered] == ["websocket.send", "websocket.close"]
