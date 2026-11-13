"""실시간 채널(design.md §7.2). 토큰은 URL 로그에 남지 않도록 Authorization 헤더로 받는다."""

from __future__ import annotations

import asyncio
import logging
from contextlib import suppress
from datetime import datetime, timezone

import anyio
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from pydantic import ValidationError

from ..api.deps import Container, bearer_token
from ..errors import ApiError
from ..models.schemas import LocationUpdate
from ..services.tokens import Claims
from . import events as ev
from .events import Event

logger = logging.getLogger(__name__)
router = APIRouter()

CLOSE_UNAUTHORIZED = 4401


def _error_event(group_id: str, code: str, message: str) -> Event:
    return Event(ev.ERROR, group_id, {"code": code, "message": message})


async def _authorize(socket: WebSocket, container: Container, group_id: str) -> Claims | None:
    """거절 사유를 클라이언트가 구분할 수 있도록 먼저 연결을 받고 사유를 보낸 뒤 닫는다."""
    await socket.accept()
    try:
        claims = container.tokens.verify(bearer_token(socket.headers.get("authorization")))
        if claims.group_id != group_id:
            raise ApiError(403, "NOT_A_MEMBER", "이 그룹의 참여 차량이 아닙니다.")
        await run_in_threadpool(container.groups.assert_member, group_id, claims.vehicle_id)
    except ApiError as error:
        await socket.send_json(_error_event(group_id, error.code, error.message).to_wire())
        await socket.close(code=CLOSE_UNAUTHORIZED)
        return None
    return claims


async def _handle_message(
    socket: WebSocket, container: Container, group_id: str, vehicle_id: str, message: dict
) -> None:
    kind = message.get("type")
    payload = message.get("payload") or {}
    groups = container.groups
    if kind == "location.updated":
        update = LocationUpdate.model_validate(payload)
        events = await run_in_threadpool(groups.update_location, group_id, vehicle_id, update)
    elif kind == "eta.requested":
        events = await run_in_threadpool(groups.refresh_eta, group_id, vehicle_id)
    elif kind == "presence.heartbeat":
        return
    else:
        await container.connections.send_to(
            socket, _error_event(group_id, "UNKNOWN_EVENT", "지원하지 않는 이벤트입니다.")
        )
        return
    await container.connections.publish(events)


async def _announce_presence(container: Container, group_id: str, vehicle_id: str) -> None:
    try:
        events = await run_in_threadpool(container.groups.touch_presence, group_id, vehicle_id)
    except ApiError:
        return
    await container.connections.publish(events)


async def _close_at_expiry(
    socket: WebSocket, container: Container, group_id: str, vehicle_id: str, expires_at: datetime
) -> None:
    """수신만 하는 연결도 만료 시각 이후에는 다른 차량의 위치를 받지 못하게 한다."""
    remaining = (expires_at - datetime.now(timezone.utc)).total_seconds()
    await asyncio.sleep(max(0.0, remaining))
    connections = container.connections
    # 닫는 도중 전파가 끼어들지 않도록 전파 대상에서 먼저 뺀다.
    removed = connections.disconnect(group_id, vehicle_id, socket)
    await connections.send_to(socket, _error_event(group_id, "TOKEN_EXPIRED", "접속 정보가 만료되었습니다."))
    await connections.close(socket, CLOSE_UNAUTHORIZED)
    if removed:
        await _announce_presence(container, group_id, vehicle_id)


@router.websocket("/ws/groups/{group_id}")
async def group_channel(socket: WebSocket, group_id: str) -> None:
    container: Container = socket.app.state.container
    claims = await _authorize(socket, container, group_id)
    if claims is None:
        return

    vehicle_id = claims.vehicle_id
    connections = container.connections
    await connections.connect(group_id, vehicle_id, socket)
    expiry = asyncio.create_task(_close_at_expiry(socket, container, group_id, vehicle_id, claims.expires_at))
    await _announce_presence(container, group_id, vehicle_id)
    try:
        while True:
            message = await socket.receive_json()
            try:
                await _handle_message(socket, container, group_id, vehicle_id, message)
            except ApiError as error:
                await connections.send_to(socket, _error_event(group_id, error.code, error.message))
            except ValidationError:
                await connections.send_to(
                    socket, _error_event(group_id, "INVALID_PAYLOAD", "이벤트 형식이 올바르지 않습니다.")
                )
    except WebSocketDisconnect:
        pass
    except ValueError:
        logger.info("JSON이 아닌 메시지로 연결을 닫습니다.")
        await connections.close(socket, 1003)
    except RuntimeError:
        # 메시지를 처리하는 사이 서버가 먼저 닫은 연결이다(세션 종료, 탈퇴, 토큰 만료).
        logger.debug("이미 닫힌 연결입니다.")
    finally:
        expiry.cancel()
        with suppress(asyncio.CancelledError, Exception):
            await expiry
        if connections.disconnect(group_id, vehicle_id, socket):
            # 연결 태스크가 취소되는 중이어도 남은 차량은 오프라인 상태를 알아야 한다.
            with anyio.CancelScope(shield=True):
                await _announce_presence(container, group_id, vehicle_id)
