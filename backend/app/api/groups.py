"""REST API(design.md §7.1)."""

from __future__ import annotations

import anyio.from_thread
from fastapi import APIRouter, Request

from ..errors import ApiError
from ..models.schemas import (
    CreateGroupRequest,
    DestinationRequest,
    FreeDriveRequest,
    JoinGroupRequest,
    LocationSharingRequest,
    LocationUpdate,
    MeetingPurposeRequest,
    SelectMeetingPointRequest,
)
from .deps import ClaimsDep, ContainerDep, publish

router = APIRouter(prefix="/api/v1/groups", tags=["groups"])


@router.post("", status_code=201)
def create_group(body: CreateGroupRequest, container: ContainerDep) -> dict:
    return container.groups.create_group(body).to_body()


@router.post("/join", status_code=201)
def join_group(body: JoinGroupRequest, request: Request, container: ContainerDep) -> dict:
    client = request.client.host if request.client else "unknown"
    if not container.join_limiter.allow(client):
        raise ApiError(429, "TOO_MANY_ATTEMPTS", "시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.")
    grant, events = container.groups.join_group(body)
    publish(container, events)
    return grant.to_body()


@router.get("/{group_id}")
def get_group(group_id: str, claims: ClaimsDep, container: ContainerDep) -> dict:
    return container.groups.snapshot(group_id, claims.vehicle_id)


@router.patch("/{group_id}/destination")
def set_destination(group_id: str, body: DestinationRequest, claims: ClaimsDep, container: ContainerDep) -> dict:
    view, events = container.groups.set_destination(group_id, claims.vehicle_id, body)
    publish(container, events)
    return view


@router.post("/{group_id}/free-drive")
def start_free_drive(group_id: str, body: FreeDriveRequest, claims: ClaimsDep, container: ContainerDep) -> dict:
    """목적지 없는 드라이브: 시간과 테마에 맞는 명소를 골라 그룹 목적지로 정한다(그룹장 전용)."""
    view, events = container.groups.start_free_drive(group_id, claims.vehicle_id, body)
    publish(container, events)
    return view


@router.patch("/{group_id}/meeting-purpose")
def set_meeting_purpose(group_id: str, body: MeetingPurposeRequest, claims: ClaimsDep, container: ContainerDep) -> dict:
    view, events = container.groups.set_purpose(group_id, claims.vehicle_id, body)
    publish(container, events)
    return view


@router.patch("/{group_id}/location-sharing")
def set_location_sharing(group_id: str, body: LocationSharingRequest, claims: ClaimsDep, container: ContainerDep) -> dict:
    view, events = container.groups.set_location_sharing(group_id, claims.vehicle_id, body.enabled)
    publish(container, events)
    return view


@router.post("/{group_id}/locations", status_code=202)
def update_location(group_id: str, body: LocationUpdate, claims: ClaimsDep, container: ContainerDep) -> dict:
    events = container.groups.update_location(group_id, claims.vehicle_id, body)
    publish(container, events)
    return {"accepted": bool(events)}


@router.post("/{group_id}/recommendations", status_code=201)
def request_recommendations(group_id: str, claims: ClaimsDep, container: ContainerDep) -> dict:
    container.groups.assert_member(group_id, claims.vehicle_id)
    payload, events = container.recommendations.compute(group_id, claims.vehicle_id)
    publish(container, events)
    return payload


@router.get("/{group_id}/recommendations")
def get_recommendations(group_id: str, claims: ClaimsDep, container: ContainerDep) -> dict:
    container.groups.assert_member(group_id, claims.vehicle_id)
    return container.recommendations.latest(group_id, claims.vehicle_id)


@router.post("/{group_id}/meeting-point")
def select_meeting_point(group_id: str, body: SelectMeetingPointRequest, claims: ClaimsDep, container: ContainerDep) -> dict:
    view, events = container.groups.select_meeting_point(group_id, claims.vehicle_id, body)
    publish(container, events)
    return view


@router.post("/{group_id}/leave", status_code=204)
def leave_group(group_id: str, claims: ClaimsDep, container: ContainerDep) -> None:
    events = container.groups.leave(group_id, claims.vehicle_id)
    publish(container, events)
    # 탈퇴한 차량이 다른 차량의 위치를 더 받지 못하도록 연결을 끊는다.
    anyio.from_thread.run(container.connections.close_vehicle, group_id, claims.vehicle_id)


@router.post("/{group_id}/complete", status_code=204)
def complete_group(group_id: str, claims: ClaimsDep, container: ContainerDep) -> None:
    events = container.groups.complete(group_id, claims.vehicle_id)
    publish(container, events)
    anyio.from_thread.run(container.connections.close_group, group_id)
