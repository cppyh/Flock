"""추천 요청 처리. 그룹 상태로 엔진 입력을 만들고 결과를 저장·전파한다."""

from __future__ import annotations

import logging
import uuid
from collections.abc import Callable
from datetime import datetime, timedelta, timezone

from sqlalchemy import select, update
from sqlalchemy.orm import Session, sessionmaker

from ..config import Settings
from ..errors import ApiError
from ..integrations.routing.base import RoutingError
from ..models.orm import Group, RecommendationRun
from ..optimization.engine import RecommendationEngine
from ..optimization.types import (
    CandidateMetrics,
    Destination,
    LatLon,
    MeetingPurpose,
    RecommendationRequest,
    RecommendationResult,
    ScoredCandidate,
    VehicleInput,
)
from ..ws import events as ev
from ..ws.events import Event
from .views import can_see_destination, destination_hidden, hide_destination, isoformat, leader_ids

logger = logging.getLogger(__name__)

MIN_VEHICLES = 2
K_REDUCTION_RATIO = 0.7
PHASE_CLOSED = "CLOSED"


def composition_changed(group: Group, payload: dict, now: datetime, max_age: timedelta) -> bool:
    """추천을 계산한 뒤 차량 구성이 바뀌었는지 판단한다.

    계획에 든 차량이 나갔거나 공유를 끊었으면, 또는 계산에 넣을 수 있는 차량이 새로 생겼으면 낡은 추천이다.
    공유 중인 차량의 위치가 잠시 끊긴 것은 구성 변화로 보지 않는다.
    """
    planned = set(payload["destination_comparison"]["per_vehicle_arrival_eta"])
    sharing = {p.vehicle_id for p in group.participants if p.is_active and p.location_sharing}
    usable = {p.vehicle_id for p in group.participants if p.has_usable_location(now, max_age)}
    return bool(planned - sharing or usable - planned)


def format_minutes(minutes: float) -> str:
    rounded = round(minutes)
    hours, rest = divmod(rounded, 60)
    if hours and rest:
        return f"{hours}시간 {rest}분"
    return f"{hours}시간" if hours else f"{rest}분"


def ev_reason(metrics: CandidateMetrics) -> str | None:
    """전기차가 있을 때 가장 중요한 충전 정보 한 가지."""
    plans = list(metrics.ev_plans.values())
    needing = [plan for plan in plans if plan.needs_charge]
    if needing:
        worst = max(needing, key=lambda plan: plan.extra_minutes)
        # 충전기가 없거나 합류 지점까지도 못 가 따로 들러야 하는 경우
        if not worst.charger_available or (worst.covered_minutes == 0 and worst.extra_minutes > worst.charge_minutes):
            return f"충전소 없어 따로 충전 {format_minutes(worst.extra_minutes)}"
        if worst.extra_minutes < 1:
            return f"충전 {format_minutes(worst.charge_minutes)}, 대기 중 끝남"
        return f"충전 {format_minutes(worst.charge_minutes)} 중 {format_minutes(worst.covered_minutes)} 대기와 겹침"
    bonus = max((plan.bonus_percent for plan in plans), default=0.0)
    if bonus >= 5:
        return f"대기 중 충전 +{round(bonus)}%"
    return None


def build_reason(metrics: CandidateMetrics) -> str:
    """운전 중에도 읽을 수 있도록 짧게 쓴다."""
    parts = [
        f"총 우회 {format_minutes(metrics.total_detour_minutes)}",
        f"최대 대기 {format_minutes(metrics.max_wait_minutes)}",
    ]
    if not metrics.candidate.is_destination:
        parts.append(f"동행 {format_minutes(metrics.shared_minutes)}")
    ev = ev_reason(metrics)
    if ev:
        parts.append(ev)
    return " / ".join(parts)


def _round(value: float) -> float:
    return round(value, 1)


def candidate_payload(item: ScoredCandidate, rank: int | None) -> dict:
    metrics = item.metrics
    area = metrics.candidate.rest_area
    return {
        "rank": rank,
        "place_id": metrics.candidate.id,
        "name": metrics.candidate.name,
        "latitude": metrics.candidate.position.lat,
        "longitude": metrics.candidate.position.lon,
        "direction": area.direction_label if area else None,
        "route_name": area.route_name if area else None,
        "estimated_group_meeting_time": isoformat(metrics.meet_time),
        "per_vehicle_arrival_eta": {k: isoformat(v) for k, v in metrics.arrivals.items()},
        "per_vehicle_detour_minutes": {k: _round(v) for k, v in metrics.detour_minutes.items()},
        "per_vehicle_wait_minutes": {k: _round(v) for k, v in metrics.wait_minutes.items()},
        "total_detour_minutes": _round(metrics.total_detour_minutes),
        "total_wait_minutes": _round(metrics.total_wait_minutes),
        "max_detour_minutes": _round(metrics.max_detour_minutes),
        "max_wait_minutes": _round(metrics.max_wait_minutes),
        "shared_driving_minutes": _round(metrics.shared_minutes),
        "parking_or_stop_suitability": None if area is None else round(1 - metrics.penalty, 2),
        "parking_spaces": area.parking_spaces if area else None,
        "has_restaurant": area.has_restaurant if area else None,
        "has_ev_charger": area.has_ev_charger if area else None,
        "data_date": area.data_date if area else None,
        "data_uncertain": metrics.data_uncertain,
        "is_destination_fallback": metrics.candidate.is_destination,
        "ev_plan": {k: plan.to_payload() for k, plan in metrics.ev_plans.items()} or None,
        "ev_covered_minutes": _round(metrics.ev_covered_minutes),
        "ev_extra_minutes": _round(metrics.ev_extra_minutes),
        "score": round(item.score, 3),
        "reason": build_reason(metrics),
    }


def restricted_payload(payload: dict) -> dict:
    """비공개 목적지를 가린 그룹원용 추천 결과."""
    return {
        **payload,
        "recommendations": [hide_destination(item) for item in payload["recommendations"]],
        "destination_comparison": hide_destination(payload["destination_comparison"]),
    }


def result_payload(result: RecommendationResult, recommendation_id: str, group_id: str) -> dict:
    window = None
    if result.window is not None:
        window = {"start": isoformat(result.window.start), "end": isoformat(result.window.end)}
    destination = result.destination
    destination_rank = next(
        (rank for rank, item in enumerate(result.top, 1) if item is destination), None
    )
    return {
        "recommendation_id": recommendation_id,
        "group_id": group_id,
        "computed_at": isoformat(result.computed_at),
        "recommendations": [candidate_payload(item, rank) for rank, item in enumerate(result.top, 1)],
        "destination_comparison": candidate_payload(destination, destination_rank),
        "notice": result.notice,
        "meet_window": window,
        "window_expanded": result.window_expanded,
        "change_recommended": result.change_recommended,
        "route_source": result.route_source,
        "traffic_aware": result.traffic_aware,
        "excluded_vehicles": result.excluded_vehicles,
        "stats": {
            "elapsed_seconds": round(result.elapsed_seconds, 2),
            "candidates_evaluated": result.candidates_evaluated,
            "route_queries": result.usage.logical_queries,
            "route_calls": result.usage.route_calls,
            "matrix_calls": result.usage.matrix_calls,
            "rejected": result.rejected,
        },
        "stale": False,
    }


class RecommendationService:
    def __init__(
        self,
        settings: Settings,
        session_factory: sessionmaker[Session],
        engine: RecommendationEngine,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ):
        self._settings = settings
        self._sessions = session_factory
        self._engine = engine
        self._clock = clock
        self._top_k_by_group: dict[str, int] = {}

    def compute(self, group_id: str, viewer: str | None = None) -> tuple[dict, list[Event]]:
        """viewer는 요청한 차량이다. 비공개 목적지면 그룹장이 아닌 차량에는 가린 결과를 돌려준다."""
        now = self._clock()
        with self._sessions() as db:
            group = self._require_group(db, group_id)
            request, excluded = self._build_request(group, now)
            plan_version = group.version

        top_k = self._top_k_by_group.get(group_id, self._settings.candidate_top_k)
        try:
            result = self._engine.recommend(request, top_k)
        except RoutingError as error:
            logger.warning("추천 계산 실패: %s", type(error).__name__)
            raise ApiError(
                503,
                "ROUTING_UNAVAILABLE",
                "경로 정보를 가져오지 못했습니다. 마지막 추천 결과를 확인해 주세요.",
                {"last_good": self._last_payload(group_id, viewer)},
            ) from error
        result.excluded_vehicles = excluded
        self._adapt_top_k(group_id, top_k, result.elapsed_seconds)

        recommendation_id = uuid.uuid4().hex
        payload = result_payload(result, recommendation_id, group_id)
        with self._sessions() as db, db.begin():
            self._claim_plan(db, group_id, plan_version)
            db.execute(
                update(RecommendationRun)
                .where(RecommendationRun.group_id == group_id)
                .values(is_current=False)
            )
            db.add(RecommendationRun(id=recommendation_id, group_id=group_id, created_at=now, payload=payload))
            group = self._require_group(db, group_id)
            hidden = destination_hidden(group)
            leaders = leader_ids(group)
        event = Event(
            ev.RECOMMENDATIONS_READY,
            group_id,
            payload,
            restricted_payload=restricted_payload(payload) if hidden else None,
            full_access=leaders,
        )
        return event.payload_for(viewer), [event]

    def _claim_plan(self, db: Session, group_id: str, plan_version: int) -> None:
        """계산에 쓴 그룹 상태가 그대로일 때만 결과를 저장한다.

        행을 잠그며 확인하므로 이 뒤에 오는 목적지 변경은 방금 저장한 결과까지 함께 무효화한다.
        """
        unchanged = db.execute(
            update(Group)
            .where(Group.id == group_id, Group.version == plan_version, Group.phase != PHASE_CLOSED)
            .values(version=Group.version)
            .execution_options(synchronize_session=False)
        )
        if unchanged.rowcount == 0:
            raise ApiError(409, "RECOMMENDATION_OUTDATED", "계산하는 동안 그룹 설정이 바뀌었습니다. 다시 추천을 받아 주세요.")

    def latest(self, group_id: str, viewer: str | None = None) -> dict:
        payload = self._last_payload(group_id, viewer)
        if payload is None:
            raise ApiError(404, "RECOMMENDATION_NOT_FOUND", "아직 추천 결과가 없습니다.")
        return payload

    def _last_payload(self, group_id: str, viewer: str | None) -> dict | None:
        now = self._clock()
        max_age = timedelta(minutes=self._settings.location_max_age_minutes)
        with self._sessions() as db:
            run = db.scalar(
                select(RecommendationRun)
                .where(RecommendationRun.group_id == group_id)
                .order_by(RecommendationRun.created_at.desc())
                .limit(1)
            )
            if run is None:
                return None
            group = self._require_group(db, group_id)
            outdated = composition_changed(group, run.payload, now, max_age)
            payload = run.payload if can_see_destination(group, viewer) else restricted_payload(run.payload)
            return {**payload, "stale": outdated or not run.is_current}

    def _adapt_top_k(self, group_id: str, top_k: int, elapsed_seconds: float) -> None:
        """응답 시간 목표를 넘기면 다음 추천부터 후보 수를 줄인다."""
        if elapsed_seconds > self._settings.recommendation_target_seconds:
            reduced = max(self._settings.candidate_min_k, int(top_k * K_REDUCTION_RATIO))
            self._top_k_by_group[group_id] = reduced

    def _require_group(self, db: Session, group_id: str) -> Group:
        group = db.get(Group, group_id)
        if group is None:
            raise ApiError(404, "GROUP_NOT_FOUND", "그룹을 찾을 수 없습니다.")
        return group

    def _build_request(self, group: Group, now: datetime) -> tuple[RecommendationRequest, list[str]]:
        if group.destination_lat is None:
            raise ApiError(409, "DESTINATION_REQUIRED", "공통 목적지를 먼저 설정해 주세요.")
        purpose = MeetingPurpose(group.meeting_purpose)
        if purpose is MeetingPurpose.MEAL and group.meal_time is None:
            raise ApiError(409, "MEAL_TIME_REQUIRED", "식사 목적에는 식사 시각이 필요합니다.")

        max_age = timedelta(minutes=self._settings.location_max_age_minutes)
        vehicles, excluded = [], []
        for participant in group.participants:
            if not participant.is_active:
                continue
            if participant.has_usable_location(now, max_age):
                vehicles.append(
                    VehicleInput(
                        participant.vehicle_id,
                        LatLon(participant.lat, participant.lon),
                        participant.location_at,
                        participant.battery_percent,
                    )
                )
            else:
                excluded.append(participant.vehicle_id)
        if len(vehicles) < MIN_VEHICLES:
            raise ApiError(
                409,
                "NOT_ENOUGH_VEHICLES",
                "위치를 공유 중인 차량이 2대 이상 있어야 추천할 수 있습니다.",
            )

        return (
            RecommendationRequest(
                vehicles=tuple(vehicles),
                destination=Destination(
                    group.destination_name, LatLon(group.destination_lat, group.destination_lon)
                ),
                purpose=purpose,
                now=now,
                departure_at=group.departed_at or now,
                meal_time=group.meal_time,
                current_place_id=group.selected_meeting_point_id,
            ),
            excluded,
        )

