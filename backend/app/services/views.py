"""DB 객체를 API 응답과 WebSocket 이벤트 모양으로 바꾼다(design.md §7.3)."""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, timedelta, timezone

from ..integrations.drive_spots import DriveTheme
from ..models.orm import Group, Participant

Presence = Callable[[str, str], bool]

HIDDEN_DESTINATION_NAME = "비공개 목적지"


def isoformat(moment: datetime | None) -> str | None:
    if moment is None:
        return None
    return moment.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def participant_status(participant: Participant, now: datetime, stale_after_seconds: int) -> str:
    if not participant.is_active:
        return "LEFT"
    if not participant.location_sharing:
        return "PAUSED"
    if participant.location_at is None:
        return "NO_LOCATION"
    if now - participant.location_at >= timedelta(seconds=stale_after_seconds):
        return "STALE"
    return "ACTIVE"


def participant_view(
    participant: Participant, now: datetime, stale_after_seconds: int, online: bool
) -> dict:
    return {
        "vehicle_id": participant.vehicle_id,
        "nickname": participant.nickname,
        "role": participant.role,
        "is_simulated": participant.is_simulated,
        "status": participant_status(participant, now, stale_after_seconds),
        "online": online,
        "location_sharing": participant.location_sharing,
        "lat": participant.lat,
        "lon": participant.lon,
        "heading": participant.heading,
        "accuracy_m": participant.accuracy_m,
        "location_source": participant.location_source,
        "timestamp": isoformat(participant.location_at),
        "battery_percent": participant.battery_percent,
        "eta_seconds": participant.eta_seconds,
        "eta_source": participant.eta_source,
        "eta_updated_at": isoformat(participant.eta_updated_at),
        "delayed": participant.delayed,
        "arrived": participant.arrived_at is not None,
    }


def destination_hidden(group: Group) -> bool:
    """비공개 드라이브의 목적지가 아직 그룹장 외에게 가려져 있는지."""
    return (
        bool(group.destination_private)
        and group.destination_revealed_at is None
        and group.destination_lat is not None
    )


def leader_ids(group: Group) -> frozenset[str]:
    return frozenset(p.vehicle_id for p in group.participants if p.is_active and p.role == "LEADER")


def can_see_destination(group: Group, vehicle_id: str | None) -> bool:
    return not destination_hidden(group) or vehicle_id in leader_ids(group)


def hide_destination(item: dict | None) -> dict | None:
    """추천 후보 중 목적지 항목의 이름과 좌표를 가린다. 도착 예정 시각 등 계획 값은 그대로 둔다."""
    if item is None or not item.get("is_destination_fallback"):
        return item
    return {**item, "name": HIDDEN_DESTINATION_NAME, "latitude": None, "longitude": None}


def free_drive_view(group: Group, hidden: bool) -> dict | None:
    if group.drive_hours is None or group.drive_theme is None:
        return None
    plan = group.drive_plan or {}
    theme = DriveTheme(group.drive_theme)
    private = bool(group.destination_private)
    return {
        "plan_id": plan.get("plan_id"),
        "hours": group.drive_hours,
        "theme": theme.value,
        "theme_label": theme.label,
        "private": private,
        "revealed": not private or group.destination_revealed_at is not None,
        "round_trip_minutes": plan.get("round_trip_minutes"),
        "one_way_minutes": plan.get("one_way_minutes"),
        "tolerance_minutes": plan.get("tolerance_minutes"),
        "within_tolerance": plan.get("within_tolerance"),
        "notice": plan.get("notice"),
        "region": None if hidden else plan.get("region"),
        "description": None if hidden else plan.get("description"),
    }


def group_view(
    group: Group,
    now: datetime,
    stale_after_seconds: int,
    presence: Presence,
    reveal_destination: bool = True,
) -> dict:
    """reveal_destination이 False면 비공개 목적지를 가린 그룹원용 화면을 만든다."""
    hidden = destination_hidden(group) and not reveal_destination
    destination = None
    if group.destination_lat is not None and not hidden:
        destination = {
            "name": group.destination_name,
            "lat": group.destination_lat,
            "lon": group.destination_lon,
        }
    active = [p for p in group.participants if p.is_active]
    meeting_point = hide_destination(group.meeting_point) if hidden else group.meeting_point
    return {
        "group_id": group.id,
        "name": group.name,
        "invite_code": group.invite_code,
        "invite_expires_at": isoformat(group.invite_expires_at),
        "max_vehicles": group.max_vehicles,
        "destination": destination,
        "destination_hidden": hidden,
        "free_drive": free_drive_view(group, hidden),
        "meeting_purpose": group.meeting_purpose,
        "meal_time": isoformat(group.meal_time),
        "phase": group.phase,
        "version": group.version,
        "participants": [
            participant_view(p, now, stale_after_seconds, presence(group.id, p.vehicle_id))
            for p in active
        ],
        "selected_meeting_point_id": group.selected_meeting_point_id,
        "meeting_point": meeting_point,
        "reroute_suggested": any(p.delayed for p in active),
    }
