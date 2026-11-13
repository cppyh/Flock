"""가상 차량 시뮬레이터. 차량 B·C를 재현해 앱 1대로도 다차량 시연을 할 수 있게 한다.

그룹을 직접 만들기:
    python simulator/vehicle_simulator.py --scenario simulator/scenarios/three_cars.json

앱이 만든 그룹에 참여하기(시나리오의 첫 차량은 앱이 맡는다):
    python simulator/vehicle_simulator.py --scenario ... --invite-code ABC123 --time-scale 30 --hold-until-selected

서버에는 is_simulated=true로 참여하므로 앱 화면에 시뮬레이션 차량으로 표시된다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import websockets

EARTH_RADIUS_M = 6_371_000
ARRIVAL_SNAP_M = 50

# 전기차(시나리오에 battery_percent가 있는 차량) 배터리 모형. 서버 기본값과 같다.
EV_BATTERY_KWH = 77.4
EV_KWH_PER_KM = 0.18
EV_CHARGER_KW = 100
EV_CHARGE_LIMIT = 80


def battery_after_driving(percent: float, meters: float) -> float:
    used = meters / 1000 * EV_KWH_PER_KM / EV_BATTERY_KWH * 100
    return max(0.0, percent - used)


def battery_after_charging(percent: float, seconds: float) -> float:
    """급속 충전 한도까지만 올린다. 이미 넘었으면 그대로 둔다."""
    if percent >= EV_CHARGE_LIMIT:
        return percent
    gained = seconds / 3600 * EV_CHARGER_KW / EV_BATTERY_KWH * 100
    return min(EV_CHARGE_LIMIT, percent + gained)


def distance_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    d_lat = lat2 - lat1
    d_lon = math.radians(b[1] - a[1])
    h = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def advance(position: tuple[float, float], target: tuple[float, float], meters: float) -> tuple[float, float]:
    """목표를 향해 직선으로 이동한다. 시연 재현용이며 실제 도로를 따르지는 않는다."""
    remaining = distance_m(position, target)
    if remaining <= max(meters, ARRIVAL_SNAP_M):
        return target
    ratio = meters / remaining
    return (
        position[0] + (target[0] - position[0]) * ratio,
        position[1] + (target[1] - position[1]) * ratio,
    )


def quiet_connection_reset(loop: asyncio.AbstractEventLoop, context: dict) -> None:
    """세션이 끝나 서버가 연결을 닫으면 Windows의 asyncio가 이미 끊긴 소켓을 정리하다 ConnectionResetError를
    경고로 찍는다(WinError 10054). 동작에는 영향이 없으므로 이 경우만 조용히 넘기고 나머지는 그대로 보여 준다.
    """
    if isinstance(context.get("exception"), ConnectionResetError):
        return
    loop.default_exception_handler(context)


class SimulatorError(Exception):
    """사용자가 고칠 수 있는 실패. 스택 대신 안내 문구만 보여 준다."""


def explain_join_failure(response: httpx.Response) -> str:
    try:
        error = response.json()["error"]
        code, message = error["code"], error["message"]
    except (ValueError, KeyError, TypeError):
        code, message = str(response.status_code), response.text[:200]
    if code == "GROUP_FULL":
        return (
            f"{message} 시뮬레이터는 재실행해도 이전 차량 자리를 이어받지 못합니다. "
            "앱에서 그룹을 새로 만든 뒤 새 초대 코드로 다시 실행해 주세요."
        )
    return f"그룹에 참여하지 못했습니다({code}): {message}"


@dataclass
class SimulatedVehicle:
    label: str
    nickname: str
    position: tuple[float, float]
    vehicle_id: str = ""
    token: str = ""
    target: tuple[float, float] | None = None
    arrived: bool = False
    battery_percent: float | None = None
    target_has_charger: bool = False

    def step(self, meters: float, seconds: float) -> None:
        """한 주기만큼 이동한다. 전기차는 달리면 배터리가 줄고, 충전기가 있는 합류 지점에서 기다리면 충전한다."""
        if self.target is not None and not self.arrived:
            before = self.position
            self.position = advance(self.position, self.target, meters)
            self.arrived = self.position == self.target
            if self.battery_percent is not None:
                self.battery_percent = battery_after_driving(self.battery_percent, distance_m(before, self.position))
        elif self.arrived and self.target_has_charger and self.battery_percent is not None:
            self.battery_percent = battery_after_charging(self.battery_percent, seconds)

    def location_payload(self) -> dict:
        payload = {
            "lat": self.position[0],
            "lon": self.position[1],
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "source": "SIMULATOR",
        }
        if self.battery_percent is not None:
            payload["battery_percent"] = round(self.battery_percent, 1)
        return payload


class Simulation:
    def __init__(self, args: argparse.Namespace, scenario: dict):
        self._args = args
        self._scenario = scenario
        self._http = httpx.AsyncClient(base_url=args.server, timeout=30)
        self._ws_base = args.server.replace("http", "ws", 1)
        self._speed_mps = scenario.get("speed_kmh", 90) / 3.6
        self._interval = args.interval or scenario.get("update_interval_seconds", 5)
        self._stop = asyncio.Event()
        self.group_id = ""
        self.invite_code = args.invite_code or ""

    async def run(self) -> None:
        asyncio.get_running_loop().set_exception_handler(quiet_connection_reset)
        vehicles = [
            SimulatedVehicle(v["label"], v["nickname"], (v["lat"], v["lon"]), battery_percent=v.get("battery_percent"))
            for v in self._scenario["vehicles"]
        ]
        try:
            if self.invite_code:
                # 첫 차량은 실제 앱이 맡으므로 나머지만 참여시킨다.
                vehicles = vehicles[1:]
                for vehicle in vehicles:
                    await self._join(vehicle)
            else:
                await self._create_with_first(vehicles[0])
                for vehicle in vehicles[1:]:
                    await self._join(vehicle)
                await self._configure_group(vehicles[0])
            for vehicle in vehicles:
                await self._share_location(vehicle)
            print(f"그룹 {self.group_id} / 초대 코드 {self.invite_code} / 시뮬레이션 차량 {len(vehicles)}대", flush=True)

            tasks = [asyncio.create_task(self._drive(v)) for v in vehicles]
            if self._args.duration:
                tasks.append(asyncio.create_task(self._stop_after(self._args.duration)))
            await self._stop.wait()
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        finally:
            await self._http.aclose()

    async def _stop_after(self, seconds: float) -> None:
        await asyncio.sleep(seconds)
        self._stop.set()

    async def _create_with_first(self, vehicle: SimulatedVehicle) -> None:
        response = await self._http.post(
            "/api/v1/groups",
            json={"name": self._scenario["name"][:40], "nickname": vehicle.nickname, "is_simulated": True},
        )
        response.raise_for_status()
        self._apply_grant(vehicle, response.json())

    async def _join(self, vehicle: SimulatedVehicle) -> None:
        response = await self._http.post(
            "/api/v1/groups/join",
            json={"invite_code": self.invite_code, "nickname": vehicle.nickname, "is_simulated": True},
        )
        if response.is_error:
            raise SimulatorError(explain_join_failure(response))
        self._apply_grant(vehicle, response.json())

    def _apply_grant(self, vehicle: SimulatedVehicle, grant: dict) -> None:
        vehicle.vehicle_id = grant["vehicle_id"]
        vehicle.token = grant["access_token"]
        self.group_id = grant["group"]["group_id"]
        self.invite_code = grant["group"]["invite_code"]
        self._retarget(vehicle, grant["group"])

    async def _configure_group(self, leader: SimulatedVehicle) -> None:
        destination = self._scenario["destination"]
        response = await self._http.patch(
            f"/api/v1/groups/{self.group_id}/destination",
            json=destination,
            headers=self._auth(leader),
        )
        response.raise_for_status()
        purpose = {"meeting_purpose": self._scenario.get("meeting_purpose", "ANY")}
        if "meal_offset_minutes" in self._scenario:
            meal_time = datetime.now(timezone.utc) + timedelta(minutes=self._scenario["meal_offset_minutes"])
            purpose["meal_time"] = meal_time.isoformat()
        response = await self._http.patch(
            f"/api/v1/groups/{self.group_id}/meeting-purpose",
            json=purpose,
            headers=self._auth(leader),
        )
        response.raise_for_status()

    async def _share_location(self, vehicle: SimulatedVehicle) -> None:
        response = await self._http.patch(
            f"/api/v1/groups/{self.group_id}/location-sharing",
            json={"enabled": True},
            headers=self._auth(vehicle),
        )
        response.raise_for_status()

    def _auth(self, vehicle: SimulatedVehicle) -> dict[str, str]:
        return {"Authorization": f"Bearer {vehicle.token}"}

    def _retarget(self, vehicle: SimulatedVehicle, group: dict) -> None:
        point = group.get("meeting_point")
        # 비공개 드라이브의 그룹원에게는 목적지가 오지 않는다(None). 합류 지점만 보고 간다.
        destination = group.get("destination")
        target = None
        has_charger = False
        if point and point.get("latitude") is not None:
            target = (point["latitude"], point["longitude"])
            has_charger = bool(point.get("has_ev_charger"))
        elif destination and not self._args.hold_until_selected:
            target = (destination["lat"], destination["lon"])
        if target is not None and target != vehicle.target:
            vehicle.target = target
            vehicle.target_has_charger = has_charger
            vehicle.arrived = False

    async def _drive(self, vehicle: SimulatedVehicle) -> None:
        """끊기면 재접속하고 최신 그룹 상태로 목표를 다시 맞춘다."""
        url = f"{self._ws_base}/ws/groups/{self.group_id}"
        while not self._stop.is_set():
            try:
                async with websockets.connect(url, additional_headers=self._auth(vehicle)) as socket:
                    await self._resync(vehicle)
                    await asyncio.gather(self._send_loop(vehicle, socket), self._receive_loop(vehicle, socket))
            except (OSError, websockets.ConnectionClosedError, websockets.InvalidHandshake):
                await asyncio.sleep(1)
            except websockets.ConnectionClosedOK:
                return

    async def _resync(self, vehicle: SimulatedVehicle) -> None:
        response = await self._http.get(f"/api/v1/groups/{self.group_id}", headers=self._auth(vehicle))
        if response.status_code == 200:
            self._retarget(vehicle, response.json())

    async def _send_loop(self, vehicle: SimulatedVehicle, socket) -> None:
        step_m = self._speed_mps * self._interval * self._args.time_scale
        step_s = self._interval * self._args.time_scale
        while True:
            vehicle.step(step_m, step_s)
            await socket.send(json.dumps({"type": "location.updated", "payload": vehicle.location_payload()}))
            await asyncio.sleep(self._interval)

    async def _receive_loop(self, vehicle: SimulatedVehicle, socket) -> None:
        async for raw in socket:
            event = json.loads(raw)
            kind = event["type"]
            if kind in ("meeting_point.selected", "group.updated"):
                self._retarget(vehicle, event["payload"]["group"])
                print(f"[{vehicle.label}] {kind}", flush=True)
            elif kind == "session.closed":
                print(f"[{vehicle.label}] 세션 종료", flush=True)
                self._stop.set()
                return
            elif kind == "error":
                print(f"[{vehicle.label}] 오류: {event['payload'].get('code')}", flush=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="MeetRoute 가상 차량 시뮬레이터")
    parser.add_argument("--scenario", type=Path, required=True)
    parser.add_argument("--server", default="http://127.0.0.1:8000")
    parser.add_argument("--invite-code", help="앱이 만든 그룹에 참여할 때 사용")
    parser.add_argument("--time-scale", type=float, default=1.0, help="이동 속도 배율(시연용)")
    parser.add_argument(
        "--hold-until-selected",
        action="store_true",
        help="합류 지점이 정해질 때까지 출발지에 머문다. 시연 때 추천 결과가 흔들리지 않는다",
    )
    parser.add_argument("--interval", type=float, help="위치 전송 주기(초). 기본값은 시나리오 설정")
    parser.add_argument("--duration", type=float, help="이 시간(초)이 지나면 종료")
    return parser.parse_args(argv)


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    scenario = json.loads(args.scenario.read_text(encoding="utf-8"))
    try:
        asyncio.run(Simulation(args, scenario).run())
    except KeyboardInterrupt:
        pass
    except SimulatorError as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
