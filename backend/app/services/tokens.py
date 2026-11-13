"""만료형 접근 토큰. 초대 코드는 인증이 아니라 이 토큰을 받기 위한 초대 수단이다."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import jwt

from ..errors import ApiError

ALGORITHM = "HS256"


@dataclass(frozen=True)
class Claims:
    group_id: str
    vehicle_id: str
    expires_at: datetime


class TokenService:
    def __init__(self, secret: str, ttl_minutes: int):
        self._secret = secret
        self._ttl = timedelta(minutes=ttl_minutes)

    def issue(self, group_id: str, vehicle_id: str, now: datetime) -> tuple[str, datetime]:
        expires_at = now + self._ttl
        token = jwt.encode(
            {"sub": vehicle_id, "grp": group_id, "exp": expires_at},
            self._secret,
            algorithm=ALGORITHM,
        )
        return token, expires_at

    def verify(self, token: str) -> Claims:
        try:
            payload = jwt.decode(token, self._secret, algorithms=[ALGORITHM])
        except jwt.ExpiredSignatureError as error:
            raise ApiError(401, "TOKEN_EXPIRED", "접속 정보가 만료되었습니다.") from error
        except jwt.InvalidTokenError as error:
            raise ApiError(401, "TOKEN_INVALID", "접속 정보가 올바르지 않습니다.") from error
        return Claims(payload["grp"], payload["sub"], datetime.fromtimestamp(payload["exp"], tz=timezone.utc))
