"""그룹 세션 관리. 상태를 바꾸는 메서드는 전파할 이벤트 목록을 돌려준다."""

from __future__ import annotations

import logging
import random
import secrets
import threading
import uuid
from collections.abc import Callable, Sequence
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, or_, select, update
from sqlalchemy.orm import Session, sessionmaker

from ..config import Settings
from ..errors import ApiError
from ..integrations.drive_spots import DriveSpot
from ..integrations.routing.base import RoutingError, RoutingProvider
from ..models.orm import ERASED_LOCATION, Group, Participant, RecommendationRun
from ..models.schemas import (
    CreateGroupRequest,
    DestinationRequest,
    FreeDriveRequest,
    JoinGroupRequest,
    LocationUpdate,
    MeetingPurposeRequest,
    SelectMeetingPointRequest,
)
from ..optimization.engine import DESTINATION_ID
from ..optimization.geo import haversine_m
from ..optimization.types import LatLon, MeetingPurpose
from ..ws import events as ev
from ..ws.events import Event
from . import free_drive
from .recommendations import composition_changed
from .tokens import TokenService
from .views import (
    Presence,
    can_see_destination,
    destination_hidden,
    group_view,
    isoformat,
    leader_ids,
    participant_view,
)

logger = logging.getLogger(__name__)

# 혼동되는 문자(0/O, 1/I)를 뺀 초대 코드 문자
INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
INVITE_LENGTH = 6

SERVICE_AREA_LAT = (33.0, 39.0)
SERVICE_AREA_LON = (124.0, 132.0)
FUTURE_TOLERANCE_SECONDS = 60

ROLE_LEADER = "LEADER"
ROLE_MEMBER = "MEMBER"

PHASE_FORMING = "FORMING"
PHASE_EN_ROUTE = "EN_ROUTE"
PHASE_ALL_ARRIVED = "ALL_ARRIVED"
PHASE_CLOSED = "CLOSED"

ETA_SOURCE_ROUTING = "ROAD_ROUTE"
ETA_SOURCE_VEHICLE = "VEHICLE_NAV"


@dataclass(frozen=True)
class SessionGrant:
    group: dict
    vehicle_id: str
    access_token: str
    token_expires_at: str

    def to_body(self) -> dict:
        return {
            "group": self.group,
            "vehicle_id": self.vehicle_id,
            "access_token": self.access_token,
            "token_expires_at": self.token_expires_at,
        }


class GroupService:
    def __init__(
        self,
        settings: Settings,
        session_factory: sessionmaker[Session],
        tokens: TokenService,
        routing: RoutingProvider,
        presence: Presence,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
        drive_spots: Sequence[DriveSpot] = (),
        rng: random.Random | None = None,
    ):
        self._settings = settings
        self._sessions = session_factory
        self._tokens = tokens
        self._routing = routing
        self._presence = presence
        self._clock = clock
        self._drive_spots = list(drive_spots)
        self._rng = rng or random.Random()
        # 정원 초과를 막는다. PostgreSQL 전환 후에는 행 잠금으로 바꿀 수 있다.
        self._join_lock = threading.Lock()

    # ---- 세션 생성·참여·종료 ----

    def create_group(self, request: CreateGroupRequest) -> SessionGrant:
        now = self._clock()
        with self._transaction() as db:
            group = Group(
                id=uuid.uuid4().hex,
                name=request.name,
                invite_code=self._new_invite_code(db),
                invite_expires_at=now + timedelta(minutes=self._settings.invite_ttl_minutes),
                max_vehicles=self._settings.max_vehicles,
                created_at=now,
            )
            db.add(group)
            participant = self._add_participant(group, request.nickname, ROLE_LEADER, request.is_simulated, now)
            db.flush()
            return self._grant(group, participant, now)

    def join_group(self, request: JoinGroupRequest) -> tuple[SessionGrant, list[Event]]:
        now = self._clock()
        code = request.invite_code.upper()
        with self._join_lock, self._transaction() as db:
            group = db.scalar(
                select(Group).where(Group.invite_code == code, Group.phase != PHASE_CLOSED)
            )
            if group is None:
                raise ApiError(404, "INVITE_NOT_FOUND", "초대 코드를 찾을 수 없습니다.")
            if group.invite_expires_at <= now:
                raise ApiError(410, "INVITE_EXPIRED", "초대 코드가 만료되었습니다.")
            if sum(p.is_active for p in group.participants) >= group.max_vehicles:
                raise ApiError(409, "GROUP_FULL", "그룹의 참여 차량 수가 가득 찼습니다.")
            participant = self._add_participant(group, request.nickname, ROLE_MEMBER, request.is_simulated, now)
            db.flush()
            return self._grant(group, participant, now), [self._participant_event(group, participant, now)]

    def snapshot(self, group_id: str, vehicle_id: str) -> dict:
        with self._transaction() as db:
            group, _ = self._member(db, group_id, vehicle_id, allow_closed=True)
            return self._group_view(group, vehicle_id)

    def leave(self, group_id: str, vehicle_id: str) -> list[Event]:
        now = self._clock()
        with self._transaction() as db:
            group, participant = self._member(db, group_id, vehicle_id)
            self._erase_location(db, participant)
            participant.left_at = now
            events = [self._participant_event(group, participant, now)]

            remaining = [p for p in group.participants if p.is_active]
            if not remaining:
                self._close(db, group, now)
                return events + [self._closed_event(group, "EMPTY")]
            if participant.role == ROLE_LEADER:
                successor = remaining[0]
                participant.role = ROLE_MEMBER
                successor.role = ROLE_LEADER
                events.append(self._participant_event(group, successor, now))
                if destination_hidden(group):
                    # 새 그룹장은 비공개 목적지를 알아야 안내를 이어 갈 수 있다.
                    events.append(self._group_event(ev.GROUP_UPDATED, group))
            events.extend(self._arrival_events(db, group, now))
            return events

    def complete(self, group_id: str, vehicle_id: str) -> list[Event]:
        now = self._clock()
        with self._transaction() as db:
            group, _ = self._leader(db, group_id, vehicle_id)
            self._close(db, group, now)
            return [self._closed_event(group, "COMPLETED")]

    # ---- 그룹 설정 ----

    def set_destination(self, group_id: str, vehicle_id: str, request: DestinationRequest) -> tuple[dict, list[Event]]:
        with self._transaction() as db:
            group, _ = self._leader(db, group_id, vehicle_id)
            group.destination_name = request.name
            group.destination_lat = request.lat
            group.destination_lon = request.lon
            # 목적지를 직접 정하면 드라이브 코스 추천은 끝난다.
            self._clear_free_drive(group)
            self._invalidate_plan(db, group)
            return self._group_changed(group, vehicle_id)

    def start_free_drive(
        self, group_id: str, vehicle_id: str, request: FreeDriveRequest
    ) -> tuple[dict, list[Event]]:
        """목적지 없는 드라이브: 정한 시간에 맞는 명소를 골라 그룹 목적지로 둔다."""
        now = self._clock()
        target = request.hours * 60
        tolerance = self._settings.free_drive_tolerance_minutes
        with self._transaction() as db:
            group, _ = self._leader(db, group_id, vehicle_id)
            max_age = timedelta(minutes=self._settings.location_max_age_minutes)
            origins = [
                (p.vehicle_id, LatLon(p.lat, p.lon))
                for p in group.participants
                if p.has_usable_location(now, max_age)
            ]
            history = list(group.drive_history or []) if request.reroll else []
        if not origins:
            raise ApiError(409, "LOCATION_REQUIRED", "위치를 공유 중인 차량이 있어야 코스를 추천할 수 있습니다.")

        positions = [position for _, position in origins]
        spots = free_drive.prefilter(self._drive_spots, request.theme, positions, target)
        if not spots:
            raise ApiError(404, "NO_DRIVE_SPOT", "추천할 수 있는 드라이브 코스가 없습니다.")
        # 외부 경로 조회는 트랜잭션 밖에서 한다.
        try:
            durations = self._routing.matrix(positions, [spot.position for spot in spots])
        except RoutingError as error:
            logger.warning("드라이브 코스 계산 실패: %s", type(error).__name__)
            raise ApiError(503, "ROUTING_UNAVAILABLE", "경로 정보를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.") from error
        pick = free_drive.choose(
            spots, [vid for vid, _ in origins], durations, target, tolerance, history, self._rng
        )
        if pick is None:
            raise ApiError(404, "NO_DRIVE_SPOT", "추천할 수 있는 드라이브 코스가 없습니다.")

        with self._transaction() as db:
            group, _ = self._leader(db, group_id, vehicle_id)
            spot = pick.spot
            group.destination_name = spot.name
            group.destination_lat = spot.position.lat
            group.destination_lon = spot.position.lon
            group.drive_hours = request.hours
            group.drive_theme = request.theme.value
            group.destination_private = request.private
            group.destination_revealed_at = None
            group.drive_history = [*history, spot.id][-free_drive.HISTORY_LIMIT :]
            group.drive_plan = {
                # 비공개여도 그룹원이 코스가 바뀐 것을 알 수 있게 하는 값. 장소를 짐작할 수 없다.
                "plan_id": uuid.uuid4().hex[:12],
                "spot_id": spot.id,
                "region": spot.region,
                "description": spot.description,
                "round_trip_minutes": round(pick.round_trip_minutes),
                "one_way_minutes": round(pick.mean_one_way_minutes),
                "per_vehicle_one_way_minutes": {k: round(v) for k, v in pick.one_way_minutes.items()},
                "tolerance_minutes": tolerance,
                "within_tolerance": pick.within_tolerance,
                "candidates_in_window": pick.in_window,
                "notice": free_drive.drive_notice(pick, request.theme, request.hours, now),
            }
            self._invalidate_plan(db, group)
            return self._group_changed(group, vehicle_id)

    def set_purpose(self, group_id: str, vehicle_id: str, request: MeetingPurposeRequest) -> tuple[dict, list[Event]]:
        if request.meeting_purpose is MeetingPurpose.MEAL and request.meal_time is None:
            raise ApiError(422, "MEAL_TIME_REQUIRED", "식사 목적에는 식사 시각이 필요합니다.")
        with self._transaction() as db:
            group, _ = self._leader(db, group_id, vehicle_id)
            group.meeting_purpose = request.meeting_purpose.value
            group.meal_time = request.meal_time if request.meeting_purpose is MeetingPurpose.MEAL else None
            self._invalidate_plan(db, group)
            return self._group_changed(group, vehicle_id)

    def set_location_sharing(self, group_id: str, vehicle_id: str, enabled: bool) -> tuple[dict, list[Event]]:
        now = self._clock()
        with self._transaction() as db:
            group, participant = self._member(db, group_id, vehicle_id)
            if enabled:
                participant.location_sharing = True
            else:
                self._erase_location(db, participant)
            event = self._participant_event(group, participant, now)
            return event.payload, [event]

    def select_meeting_point(
        self, group_id: str, vehicle_id: str, request: SelectMeetingPointRequest
    ) -> tuple[dict, list[Event]]:
        now = self._clock()
        with self._transaction() as db:
            group, _ = self._leader(db, group_id, vehicle_id)
            run = db.get(RecommendationRun, request.recommendation_id)
            max_age = timedelta(minutes=self._settings.location_max_age_minutes)
            if (
                run is None
                or run.group_id != group_id
                or not run.is_current
                or composition_changed(group, run.payload, now, max_age)
            ):
                raise ApiError(409, "RECOMMENDATION_OUTDATED", "추천 결과가 최신이 아닙니다. 다시 추천을 받아 주세요.")
            place = next(
                (item for item in _selectable(run.payload) if item["place_id"] == request.place_id),
                None,
            )
            if place is None:
                raise ApiError(404, "PLACE_NOT_FOUND", "추천 결과에 없는 장소입니다.")

            # 같은 판을 보고 들어온 요청 중 하나만 성공시킨다.
            updated = db.execute(
                update(Group)
                .where(Group.id == group_id, Group.version == request.expected_version)
                .values(
                    selected_meeting_point_id=place["place_id"],
                    meeting_point=place,
                    phase=PHASE_EN_ROUTE,
                    version=Group.version + 1,
                    # 대기실에서 추천을 미리 본 시간은 주행 시간이 아니다. 처음 합류지를 정한 때가 출발이다.
                    departed_at=group.departed_at or now,
                )
            )
            if updated.rowcount == 0:
                raise ApiError(409, "VERSION_CONFLICT", "다른 요청이 먼저 처리되었습니다. 최신 상태를 확인해 주세요.")
            db.refresh(group)
            for participant in group.participants:
                participant.arrived_at = None
                participant.delayed = False
                participant.eta_seconds = participant.eta_source = participant.eta_updated_at = None
            if place["place_id"] == DESTINATION_ID and destination_hidden(group):
                # 목적지에서 바로 만나면 모두 그곳으로 안내받아야 하므로 공개한다.
                group.destination_revealed_at = now
            event = self._group_event(
                ev.MEETING_POINT_SELECTED, group, {"selected_by": vehicle_id, "at": isoformat(now)}
            )
            return event.payload_for(vehicle_id)["group"], [event]

    # ---- 위치·ETA ----

    def update_location(self, group_id: str, vehicle_id: str, update_: LocationUpdate) -> list[Event]:
        now = self._clock()
        self._validate_fix(update_, now)
        with self._transaction() as db:
            group, participant = self._member(db, group_id, vehicle_id)
            if not participant.location_sharing:
                raise ApiError(403, "CONSENT_REQUIRED", "위치 공유에 동의해야 전송할 수 있습니다.")
            # 순서가 바뀌어 늦게 도착한 위치로 최신값을 덮지 않는다.
            if participant.location_at is not None and update_.timestamp <= participant.location_at:
                return []
            target = None
            if not self._uses_nav_eta(group, update_) and self._road_eta_due(participant, now):
                target = self._eta_target(group)

        # 외부 경로 조회는 몇 초씩 걸릴 수 있어 트랜잭션 밖에서 한다.
        road_seconds = self._road_seconds(LatLon(update_.lat, update_.lon), target)

        with self._transaction() as db:
            # 조회하는 사이 동의 철회·탈퇴·세션 종료가 끝났을 수 있다. 조건과 저장을 한 문장으로 처리해
            # 지운 좌표가 되살아나지 않게 한다. 세션 종료도 모든 차량의 공유를 끄므로 여기서 걸러진다.
            if not self._store_fix(db, group_id, vehicle_id, update_):
                return []
            group = db.get(Group, group_id)
            participant = db.get(Participant, vehicle_id)
            if self._uses_nav_eta(group, update_):
                self._store_eta(group, participant, update_.nav_remain_seconds, ETA_SOURCE_VEHICLE, now)
            elif road_seconds is not None and self._eta_target(group) == target:
                self._store_eta(group, participant, road_seconds, ETA_SOURCE_ROUTING, now)
            self._mark_arrival(group, participant, update_, now)
            return [self._participant_event(group, participant, now), *self._arrival_events(db, group, now)]

    def refresh_eta(self, group_id: str, vehicle_id: str) -> list[Event]:
        now = self._clock()
        with self._transaction() as db:
            group, participant = self._member(db, group_id, vehicle_id)
            position = None if participant.lat is None else LatLon(participant.lat, participant.lon)
            target = None if participant.arrived_at is not None else self._eta_target(group)

        seconds = None if position is None else self._road_seconds(position, target)

        with self._transaction() as db:
            # 철회와 겹쳐도 지워진 ETA가 되살아나지 않도록 먼저 이 차량의 행을 잠근다.
            db.execute(
                update(Participant)
                .where(Participant.vehicle_id == vehicle_id)
                .values(vehicle_id=Participant.vehicle_id)
                .execution_options(synchronize_session=False)
            )
            group, participant = self._member(db, group_id, vehicle_id)
            if seconds is not None and participant.location_at is not None and self._eta_target(group) == target:
                self._store_eta(group, participant, seconds, ETA_SOURCE_ROUTING, now)
            return [self._participant_event(group, participant, now)]

    def touch_presence(self, group_id: str, vehicle_id: str) -> list[Event]:
        now = self._clock()
        with self._transaction() as db:
            group, participant = self._member(db, group_id, vehicle_id, allow_closed=True)
            return [self._participant_event(group, participant, now)]

    def assert_member(self, group_id: str, vehicle_id: str) -> None:
        with self._transaction() as db:
            self._member(db, group_id, vehicle_id)

    # ---- 내부 구현 ----

    @contextmanager
    def _transaction(self):
        with self._sessions() as db:
            with db.begin():
                yield db

    def _new_invite_code(self, db: Session) -> str:
        while True:
            code = "".join(secrets.choice(INVITE_ALPHABET) for _ in range(INVITE_LENGTH))
            taken = db.scalar(select(Group.id).where(Group.invite_code == code, Group.phase != PHASE_CLOSED))
            if taken is None:
                return code

    def _add_participant(self, group: Group, nickname: str, role: str, simulated: bool, now: datetime) -> Participant:
        # 그룹 참여만으로 위치 공유가 시작되지 않도록 기본값은 꺼짐이다.
        participant = Participant(
            vehicle_id=f"car-{uuid.uuid4().hex[:12]}",
            nickname=nickname,
            role=role,
            is_simulated=simulated,
            joined_at=now,
            location_sharing=False,
        )
        group.participants.append(participant)
        return participant

    def _grant(self, group: Group, participant: Participant, now: datetime) -> SessionGrant:
        token, expires_at = self._tokens.issue(group.id, participant.vehicle_id, now)
        view = self._group_view(group, participant.vehicle_id)
        return SessionGrant(view, participant.vehicle_id, token, isoformat(expires_at))

    def _member(
        self, db: Session, group_id: str, vehicle_id: str, allow_closed: bool = False
    ) -> tuple[Group, Participant]:
        group = db.get(Group, group_id)
        participant = db.get(Participant, vehicle_id)
        if group is None or participant is None or participant.group_id != group_id or not participant.is_active:
            raise ApiError(403, "NOT_A_MEMBER", "이 그룹의 참여 차량이 아닙니다.")
        if group.phase == PHASE_CLOSED and not allow_closed:
            raise ApiError(410, "SESSION_CLOSED", "종료된 세션입니다.")
        return group, participant

    def _leader(self, db: Session, group_id: str, vehicle_id: str) -> tuple[Group, Participant]:
        group, participant = self._member(db, group_id, vehicle_id)
        if participant.role != ROLE_LEADER:
            raise ApiError(403, "LEADER_ONLY", "그룹장만 변경할 수 있습니다.")
        return group, participant

    def _bump_version(self, db: Session, group: Group) -> None:
        """진행 중인 추천 저장이 이 값을 기준으로 판단하므로 읽고 쓰지 않고 한 문장으로 올린다."""
        db.execute(update(Group).where(Group.id == group.id).values(version=Group.version + 1))
        db.refresh(group, attribute_names=["version"])

    def _erase_location(self, db: Session, participant: Participant) -> None:
        db.execute(
            update(Participant).where(Participant.vehicle_id == participant.vehicle_id).values(**ERASED_LOCATION)
        )
        db.refresh(participant)

    def _store_fix(self, db: Session, group_id: str, vehicle_id: str, update_: LocationUpdate) -> bool:
        """공유 중이고 더 최신인 위치일 때만 저장한다."""
        stored = db.execute(
            update(Participant)
            .where(
                Participant.vehicle_id == vehicle_id,
                Participant.group_id == group_id,
                Participant.left_at.is_(None),
                Participant.location_sharing.is_(True),
                or_(Participant.location_at.is_(None), Participant.location_at < update_.timestamp),
            )
            .values(
                lat=update_.lat,
                lon=update_.lon,
                heading=update_.heading,
                accuracy_m=update_.accuracy_m,
                location_source=update_.source,
                location_at=update_.timestamp,
                # 배터리를 잠깐 읽지 못한 위치가 와도 마지막 값을 지우지 않는다.
                battery_percent=func.coalesce(update_.battery_percent, Participant.battery_percent),
            )
            .execution_options(synchronize_session=False)
        )
        return stored.rowcount > 0

    def _invalidate_plan(self, db: Session, group: Group) -> None:
        self._bump_version(db, group)
        group.selected_meeting_point_id = None
        group.meeting_point = None
        group.phase = PHASE_FORMING
        db.execute(
            update(RecommendationRun)
            .where(RecommendationRun.group_id == group.id)
            .values(is_current=False)
        )
        for participant in group.participants:
            participant.arrived_at = None
            participant.delayed = False
            participant.eta_seconds = participant.eta_source = participant.eta_updated_at = None

    def _close(self, db: Session, group: Group, now: datetime) -> None:
        group.phase = PHASE_CLOSED
        group.closed_at = now
        self._bump_version(db, group)
        for participant in group.participants:
            self._erase_location(db, participant)

    def _group_view(self, group: Group, viewer: str | None, reveal: bool | None = None) -> dict:
        """viewer가 볼 수 있는 그룹 화면. 비공개 목적지는 그룹장에게만 보인다."""
        if reveal is None:
            reveal = can_see_destination(group, viewer)
        return group_view(
            group, self._clock(), self._settings.stale_after_seconds, self._presence, reveal_destination=reveal
        )

    def _group_event(self, type_: str, group: Group, extra: dict | None = None) -> Event:
        """그룹 화면을 담은 이벤트. 목적지가 가려져 있으면 그룹원에게는 가린 화면을 보낸다."""
        extra = extra or {}
        payload = {"group": self._group_view(group, None, reveal=True), **extra}
        restricted = None
        if destination_hidden(group):
            restricted = {"group": self._group_view(group, None, reveal=False), **extra}
        return Event(type_, group.id, payload, restricted_payload=restricted, full_access=leader_ids(group))

    def _group_changed(self, group: Group, viewer: str | None = None) -> tuple[dict, list[Event]]:
        event = self._group_event(ev.GROUP_UPDATED, group)
        return event.payload_for(viewer)["group"], [event]

    def _clear_free_drive(self, group: Group) -> None:
        group.drive_hours = None
        group.drive_theme = None
        group.drive_plan = None
        group.drive_history = None
        group.destination_private = False
        group.destination_revealed_at = None

    def _participant_event(self, group: Group, participant: Participant, now: datetime) -> Event:
        online = self._presence(group.id, participant.vehicle_id)
        view = participant_view(participant, now, self._settings.stale_after_seconds, online)
        return Event(ev.PARTICIPANT_UPDATED, group.id, view)

    def _closed_event(self, group: Group, reason: str) -> Event:
        return Event(ev.SESSION_CLOSED, group.id, {"reason": reason})

    def _validate_fix(self, update_: LocationUpdate, now: datetime) -> None:
        in_lat = SERVICE_AREA_LAT[0] <= update_.lat <= SERVICE_AREA_LAT[1]
        in_lon = SERVICE_AREA_LON[0] <= update_.lon <= SERVICE_AREA_LON[1]
        if not (in_lat and in_lon):
            raise ApiError(422, "LOCATION_OUT_OF_SERVICE_AREA", "서비스 지역을 벗어난 좌표입니다.")
        if update_.timestamp > now + timedelta(seconds=FUTURE_TOLERANCE_SECONDS):
            raise ApiError(422, "LOCATION_FROM_FUTURE", "측정 시각이 현재보다 미래입니다.")
        if now - update_.timestamp > timedelta(minutes=self._settings.location_max_age_minutes):
            raise ApiError(422, "LOCATION_TOO_OLD", "위치 측정 시각이 너무 오래되었습니다.")

    def _eta_target(self, group: Group) -> LatLon | None:
        if group.meeting_point is not None:
            return LatLon(group.meeting_point["latitude"], group.meeting_point["longitude"])
        if group.destination_lat is not None:
            return LatLon(group.destination_lat, group.destination_lon)
        return None

    def _uses_nav_eta(self, group: Group, update_: LocationUpdate) -> bool:
        return update_.nav_remain_seconds is not None and self._nav_matches_plan(group, update_)

    def _nav_matches_plan(self, group: Group, update_: LocationUpdate) -> bool:
        """내비의 값이 지금 합류 지점의 것일 때만 믿는다. 합류 지점이 바뀐 직후에는 이전 값이 올 수 있다."""
        if group.meeting_point is None:
            return False
        return update_.nav_target_id is None or update_.nav_target_id == group.selected_meeting_point_id

    def _road_eta_due(self, participant: Participant, now: datetime) -> bool:
        """호출량을 줄이려 주기를 둔다. 도착한 차량은 더 계산하지 않는다."""
        if participant.arrived_at is not None:
            return False
        if participant.eta_updated_at is None:
            return True
        return now - participant.eta_updated_at >= timedelta(seconds=self._settings.eta_refresh_seconds)

    def _road_seconds(self, origin: LatLon, target: LatLon | None) -> float | None:
        """실패하면 None을 돌려 이전 ETA와 갱신 시각을 그대로 둔다."""
        if target is None:
            return None
        try:
            return self._routing.matrix([origin], [target])[0][0]
        except RoutingError as error:
            logger.warning("ETA 갱신 실패: %s", type(error).__name__)
            return None

    def _store_eta(self, group: Group, participant: Participant, seconds: float, source: str, now: datetime) -> None:
        # 도착 뒤에 오는 값은 다음 구간의 시간이다. 도착 상태를 덮으면 지연으로 잘못 표시된다.
        if participant.arrived_at is not None:
            return
        participant.eta_seconds = seconds
        participant.eta_source = source
        participant.eta_updated_at = now
        participant.delayed = self._is_delayed(group, participant, seconds, now)

    def _is_delayed(self, group: Group, participant: Participant, seconds: float, now: datetime) -> bool:
        if group.meeting_point is None:
            return False
        planned = group.meeting_point["per_vehicle_arrival_eta"].get(participant.vehicle_id)
        if planned is None:
            return False
        expected = now + timedelta(seconds=seconds)
        delay = expected - datetime.fromisoformat(planned.replace("Z", "+00:00"))
        return delay >= timedelta(minutes=self._settings.reroute_delay_threshold_minutes)

    def _mark_arrival(self, group: Group, participant: Participant, update_: LocationUpdate, now: datetime) -> None:
        if group.meeting_point is None or participant.arrived_at is not None:
            return
        target = LatLon(group.meeting_point["latitude"], group.meeting_point["longitude"])
        distance = haversine_m(LatLon(update_.lat, update_.lon), target)
        arrived_by_nav = (
            update_.arrived_by_nav
            and self._nav_matches_plan(group, update_)
            and distance <= self._settings.nav_arrival_radius_m
        )
        if distance <= self._settings.arrival_radius_m or arrived_by_nav:
            participant.arrived_at = now
            participant.eta_seconds = 0
            participant.delayed = False

    def _arrival_events(self, db: Session, group: Group, now: datetime) -> list[Event]:
        active = [p for p in group.participants if p.is_active]
        if group.phase != PHASE_EN_ROUTE or not active:
            return []
        if all(p.arrived_at is not None for p in active):
            group.phase = PHASE_ALL_ARRIVED
            self._bump_version(db, group)
            if destination_hidden(group):
                # 비공개 드라이브는 모두 합류한 이 순간 목적지를 함께 공개한다.
                group.destination_revealed_at = now
                return [self._group_event(ev.GROUP_UPDATED, group, {"destination_revealed": True})]
            return self._group_changed(group)[1]
        return []


def _selectable(payload: dict) -> list[dict]:
    return [*payload["recommendations"], payload["destination_comparison"]]
