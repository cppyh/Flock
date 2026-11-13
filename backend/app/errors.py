"""클라이언트가 코드로 분기할 수 있는 API 오류."""

from __future__ import annotations


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, extra: dict | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.extra = extra or {}

    def to_body(self) -> dict:
        return {"error": {"code": self.code, "message": self.message, **self.extra}}
