"""design.md §14.1 E2E: 실제 서버와 시뮬레이터 프로세스로 전체 흐름을 재현한다.

3대 동시 진입 → 목적지 → TOP 3 → 경유지 선택 → 상태 갱신 → 종료.
앱 역할(차량 A)은 이 테스트가, 차량 B·C는 시뮬레이터가 맡는다.
실행: backend/.venv/Scripts/python -m pytest tests/e2e
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx
import pytest
from websockets.sync.client import connect

REPO_ROOT = Path(__file__).resolve().parents[2]
SCENARIO = REPO_ROOT / "simulator" / "scenarios" / "three_cars.json"
STARTUP_TIMEOUT_SECONDS = 20
ARRIVAL_TIMEOUT_SECONDS = 40


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


@pytest.fixture
def server(tmp_path):
    port = free_port()
    env = {
        **os.environ,
        "DATABASE_URL": f"sqlite:///{tmp_path / 'e2e.db'}",
        "TOKEN_SECRET": "e2e-secret-e2e-secret-e2e-secret-e2e-secret",
        "ROUTING_PROVIDER": "estimate",
        "CANDIDATE_BUFFER_KM": "15",
    }
    process = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port)],
        cwd=REPO_ROOT / "backend",
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    base_url = f"http://127.0.0.1:{port}"
    deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
    while True:
        try:
            if httpx.get(f"{base_url}/health").status_code == 200:
                break
        except httpx.HTTPError:
            pass
        if time.monotonic() > deadline:
            process.kill()
            pytest.fail("서버가 시작되지 않았습니다.")
        time.sleep(0.2)
    yield base_url
    process.terminate()
    process.wait(timeout=10)


def wait_for(condition, timeout: float, message: str):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(0.2)
    pytest.fail(message)


def test_three_vehicles_meet_and_close_session(server):
    scenario = json.loads(SCENARIO.read_text(encoding="utf-8"))
    me = scenario["vehicles"][0]
    client = httpx.Client(base_url=server, timeout=20)

    grant = client.post("/api/v1/groups", json={"name": "강릉 여행", "nickname": me["nickname"]}).json()
    group_id = grant["group"]["group_id"]
    headers = {"Authorization": f"Bearer {grant['access_token']}"}
    assert client.patch(f"/api/v1/groups/{group_id}/destination", json=scenario["destination"], headers=headers).status_code == 200
    assert client.patch(f"/api/v1/groups/{group_id}/meeting-purpose", json={"meeting_purpose": "ANY"}, headers=headers).status_code == 200
    assert client.patch(f"/api/v1/groups/{group_id}/location-sharing", json={"enabled": True}, headers=headers).status_code == 200

    simulator = subprocess.Popen(
        [
            sys.executable, str(REPO_ROOT / "simulator" / "vehicle_simulator.py"),
            "--scenario", str(SCENARIO),
            "--server", server,
            "--invite-code", grant["group"]["invite_code"],
            "--interval", "0.3",
            "--time-scale", "4000",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        def snapshot() -> dict:
            return client.get(f"/api/v1/groups/{group_id}", headers=headers).json()

        def send_my_location(lat: float, lon: float) -> None:
            body = {"lat": lat, "lon": lon, "timestamp": datetime.now(timezone.utc).isoformat(), "source": "E2E"}
            assert client.post(f"/api/v1/groups/{group_id}/locations", json=body, headers=headers).status_code == 202

        send_my_location(me["lat"], me["lon"])

        # 3대가 한 그룹이 되고 시뮬레이션 차량으로 구분되어 위치가 들어온다.
        def three_located():
            view = snapshot()
            located = [p for p in view["participants"] if p["lat"] is not None]
            return view if len(located) == 3 else None

        view = wait_for(three_located, 15, "3대의 위치가 모이지 않았습니다.")
        assert sorted(p["is_simulated"] for p in view["participants"]) == [False, True, True]

        # 앱은 WebSocket으로 추천 결과와 선택을 받는다.
        with connect(
            f"{server.replace('http', 'ws', 1)}/ws/groups/{group_id}",
            additional_headers=headers,
        ) as channel:
            result = client.post(f"/api/v1/groups/{group_id}/recommendations", headers=headers).json()
            assert 1 <= len(result["recommendations"]) <= 3
            assert len(result["destination_comparison"]["per_vehicle_arrival_eta"]) == 3
            best = result["recommendations"][0]

            def next_event(kind: str) -> dict:
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    event = json.loads(channel.recv(timeout=10))
                    if event["type"] == kind:
                        return event
                pytest.fail(f"{kind} 이벤트를 받지 못했습니다.")

            assert next_event("recommendations.ready")["payload"]["recommendation_id"] == result["recommendation_id"]

            selected = client.post(
                f"/api/v1/groups/{group_id}/meeting-point",
                json={
                    "recommendation_id": result["recommendation_id"],
                    "place_id": best["place_id"],
                    "expected_version": snapshot()["version"],
                },
                headers=headers,
            )
            assert selected.status_code == 200
            assert next_event("meeting_point.selected")["payload"]["group"]["selected_meeting_point_id"] == best["place_id"]

        # 시뮬레이션 차량이 새 합류 지점으로 이동해 도착 처리된다.
        def simulators_arrived():
            participants = snapshot()["participants"]
            others = [p for p in participants if p["is_simulated"]]
            return all(p["arrived"] for p in others)

        wait_for(simulators_arrived, ARRIVAL_TIMEOUT_SECONDS, "시뮬레이션 차량이 도착하지 못했습니다.")
        assert snapshot()["phase"] == "EN_ROUTE"

        send_my_location(best["latitude"], best["longitude"])
        assert snapshot()["phase"] == "ALL_ARRIVED"

        assert client.post(f"/api/v1/groups/{group_id}/complete", headers=headers).status_code == 204
        final = snapshot()
        assert final["phase"] == "CLOSED"
        assert all(p["lat"] is None for p in final["participants"])

        # 세션 종료를 받은 시뮬레이터는 스스로 끝난다.
        simulator.wait(timeout=15)
    finally:
        if simulator.poll() is None:
            simulator.kill()
        client.close()
