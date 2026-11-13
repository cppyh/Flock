"""그룹별 WebSocket 연결 관리."""

from __future__ import annotations

import logging
from collections import defaultdict

from fastapi import WebSocket, WebSocketDisconnect

from .events import Event

logger = logging.getLogger(__name__)

CLOSE_LEFT_GROUP = 4403
CLOSE_REPLACED = 4409
CLOSE_SESSION_ENDED = 4410


class ConnectionManager:
    def __init__(self) -> None:
        self._groups: dict[str, dict[str, WebSocket]] = defaultdict(dict)

    def is_online(self, group_id: str, vehicle_id: str) -> bool:
        return vehicle_id in self._groups.get(group_id, {})

    async def connect(self, group_id: str, vehicle_id: str, socket: WebSocket) -> None:
        """재접속이면 이전 연결을 닫고 새 연결로 교체한다."""
        previous = self._groups[group_id].get(vehicle_id)
        self._groups[group_id][vehicle_id] = socket
        if previous is not None:
            await self.close(previous, CLOSE_REPLACED)

    def disconnect(self, group_id: str, vehicle_id: str, socket: WebSocket) -> bool:
        """교체된 예전 연결이 끊긴 경우에는 현재 연결을 건드리지 않는다."""
        sockets = self._groups.get(group_id, {})
        if sockets.get(vehicle_id) is not socket:
            return False
        del sockets[vehicle_id]
        if not sockets:
            self._groups.pop(group_id, None)
        return True

    async def publish(self, events: list[Event]) -> None:
        """차량마다 받을 수 있는 내용이 다를 수 있다(비공개 목적지)."""
        for event in events:
            for vehicle_id, socket in list(self._groups.get(event.group_id, {}).items()):
                await self._send(socket, event, vehicle_id)

    async def send_to(self, socket: WebSocket, event: Event) -> None:
        await self._send(socket, event)

    async def close_vehicle(self, group_id: str, vehicle_id: str) -> None:
        socket = self._groups.get(group_id, {}).pop(vehicle_id, None)
        if socket is not None:
            await self.close(socket, CLOSE_LEFT_GROUP)

    async def close_group(self, group_id: str) -> None:
        for socket in self._groups.pop(group_id, {}).values():
            await self.close(socket, CLOSE_SESSION_ENDED)

    async def _send(self, socket: WebSocket, event: Event, vehicle_id: str | None = None) -> None:
        # 한 차량의 끊긴 연결이 다른 차량으로의 전달을 막으면 안 된다.
        # Starlette는 전송 중 OSError를 WebSocketDisconnect로 바꿔 던진다.
        try:
            await socket.send_json(event.to_wire(vehicle_id))
        except (RuntimeError, OSError, WebSocketDisconnect) as error:
            logger.info("이벤트 전달 실패(%s): %s", event.type, type(error).__name__)

    async def close(self, socket: WebSocket, code: int) -> None:
        try:
            await socket.close(code=code)
        except (RuntimeError, OSError, WebSocketDisconnect):
            pass
