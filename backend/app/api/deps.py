"""요청마다 필요한 의존성."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Annotated

import anyio.from_thread
from fastapi import Depends, Header, Request

from ..config import Settings
from ..errors import ApiError
from ..services.groups import GroupService
from ..services.rate_limit import SlidingWindowLimiter
from ..services.recommendations import RecommendationService
from ..services.tokens import Claims, TokenService
from ..ws.events import Event
from ..ws.manager import ConnectionManager


@dataclass(frozen=True)
class Container:
    settings: Settings
    tokens: TokenService
    groups: GroupService
    recommendations: RecommendationService
    connections: ConnectionManager
    join_limiter: SlidingWindowLimiter
    rest_area_count: int
    drive_spot_count: int = 0


def get_container(request: Request) -> Container:
    return request.app.state.container


ContainerDep = Annotated[Container, Depends(get_container)]


def bearer_token(authorization: str | None) -> str:
    if authorization is None or not authorization.startswith("Bearer "):
        raise ApiError(401, "TOKEN_MISSING", "접속 정보가 필요합니다.")
    return authorization.removeprefix("Bearer ")


def require_member(
    group_id: str,
    container: ContainerDep,
    authorization: Annotated[str | None, Header()] = None,
) -> Claims:
    claims = container.tokens.verify(bearer_token(authorization))
    if claims.group_id != group_id:
        raise ApiError(403, "NOT_A_MEMBER", "이 그룹의 참여 차량이 아닙니다.")
    return claims


ClaimsDep = Annotated[Claims, Depends(require_member)]


def publish(container: Container, events: list[Event]) -> None:
    """동기 엔드포인트(스레드풀)에서 이벤트 루프의 소켓으로 이벤트를 보낸다."""
    if events:
        anyio.from_thread.run(container.connections.publish, events)
