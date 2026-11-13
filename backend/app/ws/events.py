"""WebSocket 이벤트 형식(design.md §7.2)."""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from ..services.views import isoformat

PARTICIPANT_UPDATED = "participant.updated"
GROUP_UPDATED = "group.updated"
RECOMMENDATIONS_READY = "recommendations.ready"
MEETING_POINT_SELECTED = "meeting_point.selected"
SESSION_CLOSED = "session.closed"
ERROR = "error"


@dataclass(frozen=True)
class Event:
    type: str
    group_id: str
    payload: dict
    event_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    # 비공개 목적지: full_access에 든 차량(그룹장)만 payload를, 나머지는 restricted_payload를 받는다.
    restricted_payload: dict | None = None
    full_access: frozenset[str] = frozenset()

    def payload_for(self, vehicle_id: str | None) -> dict:
        """받는 차량을 모르면 가린 쪽을 보낸다."""
        if self.restricted_payload is not None and vehicle_id not in self.full_access:
            return self.restricted_payload
        return self.payload

    def to_wire(self, vehicle_id: str | None = None) -> dict:
        return {
            "type": self.type,
            "group_id": self.group_id,
            "timestamp": isoformat(self.timestamp),
            "payload": self.payload_for(vehicle_id),
            "event_id": self.event_id,
        }
