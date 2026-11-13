"""추천 엔진이 쓰는 값 객체. 서버 프레임워크와 DB에 의존하지 않는다."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

from .ev import EvConfig, EvPlan


class MeetingPurpose(str, Enum):
    DEPART_TOGETHER = "DEPART_TOGETHER"
    REST = "REST"
    MEAL = "MEAL"
    ANY = "ANY"


@dataclass(frozen=True)
class LatLon:
    lat: float
    lon: float


@dataclass(frozen=True)
class RestArea:
    id: str
    name: str
    route_name: str
    position: LatLon
    kind: str
    bidirectional: bool
    toward: str | None
    toward_point: LatLon | None
    open_start: str
    open_end: str
    parking_spaces: int | None
    has_restaurant: bool
    has_ev_charger: bool
    has_gas_station: bool
    has_toilet: bool
    has_store: bool
    data_date: str

    @property
    def direction_label(self) -> str:
        return "양방향" if self.bidirectional else f"{self.toward} 방향"


@dataclass(frozen=True)
class Destination:
    name: str
    position: LatLon


@dataclass(frozen=True)
class VehicleInput:
    vehicle_id: str
    position: LatLon
    measured_at: datetime
    # 전기차가 보낸 배터리 잔량(%). 없으면 충전 계획을 세우지 않는다.
    battery_percent: float | None = None


@dataclass(frozen=True)
class RecommendationRequest:
    vehicles: tuple[VehicleInput, ...]
    destination: Destination
    purpose: MeetingPurpose
    now: datetime
    departure_at: datetime
    meal_time: datetime | None = None
    current_place_id: str | None = None


@dataclass(frozen=True)
class MeetWindow:
    start: datetime | None
    end: datetime | None

    def contains(self, moment: datetime) -> bool:
        if self.start is not None and moment < self.start:
            return False
        return self.end is None or moment <= self.end


@dataclass(frozen=True)
class EngineConfig:
    candidate_buffer_m: float = 2000
    top_k: int = 15
    detour_max_minutes: float = 20
    normalize_min_range_minutes: float = 3
    alpha: float = 1.0
    beta: float = 0.7
    gamma: float = 0.5
    delta: float = 0.3
    epsilon: float = 0.6
    epsilon_depart_together: float = 1.2
    depart_together_window_minutes: float = 60
    rest_window_start_minutes: float = 90
    rest_window_end_minutes: float = 150
    meal_window_minutes: float = 30
    window_expansion_minutes: float = 30
    reroute_min_improvement_minutes: float = 5
    # 전기차 충전 때문에 그룹 전체가 더 기다리는 시간의 가중치
    zeta: float = 1.0
    ev: EvConfig = field(default_factory=EvConfig)

    def epsilon_for(self, purpose: MeetingPurpose) -> float:
        if purpose is MeetingPurpose.DEPART_TOGETHER:
            return self.epsilon_depart_together
        return self.epsilon


@dataclass(frozen=True)
class Candidate:
    """비교 대상 장소. rest_area가 없으면 공통 목적지다."""

    id: str
    name: str
    position: LatLon
    rest_area: RestArea | None = None

    @property
    def is_destination(self) -> bool:
        return self.rest_area is None


@dataclass(frozen=True)
class CandidateMetrics:
    candidate: Candidate
    to_seconds: dict[str, float]
    after_seconds: float
    arrivals: dict[str, datetime]
    meet_time: datetime
    detour_minutes: dict[str, float]
    wait_minutes: dict[str, float]
    total_detour_minutes: float
    total_wait_minutes: float
    max_detour_minutes: float
    max_wait_minutes: float
    shared_minutes: float
    penalty: float
    data_uncertain: bool
    ev_plans: dict[str, EvPlan] = field(default_factory=dict)

    @property
    def ev_covered_minutes(self) -> float:
        """기다리는 동안 충전으로 쓴 시간. 이 만큼은 버리는 대기가 아니다."""
        return sum(plan.covered_minutes for plan in self.ev_plans.values())

    @property
    def ev_extra_minutes(self) -> float:
        """충전이 끝나기를 그룹 전체가 더 기다려야 하는 시간. 가장 오래 걸리는 차량이 기준이다."""
        return max((plan.extra_minutes for plan in self.ev_plans.values()), default=0.0)

    @property
    def effective_wait_minutes(self) -> float:
        return self.total_wait_minutes - self.ev_covered_minutes

    @property
    def group_delay_minutes(self) -> float:
        """합류하지 않았을 때보다 그룹 전체가 목적지에 늦게 도착하는 시간의 합."""
        return (
            self.total_detour_minutes
            + self.effective_wait_minutes
            + self.ev_extra_minutes * len(self.arrivals)
        )


@dataclass(frozen=True)
class ScoredCandidate:
    metrics: CandidateMetrics
    score: float


@dataclass
class ApiUsage:
    route_calls: int = 0
    matrix_calls: int = 0
    matrix_elements: int = 0

    @property
    def logical_queries(self) -> int:
        """design.md §8.5의 n + n·K + K에 해당하는 경로 조회 수."""
        return self.route_calls + self.matrix_elements


@dataclass
class RecommendationResult:
    top: list[ScoredCandidate]
    destination: ScoredCandidate
    notice: str | None
    window: MeetWindow | None
    window_expanded: bool
    computed_at: datetime
    elapsed_seconds: float
    route_source: str
    traffic_aware: bool
    usage: ApiUsage
    rejected: dict[str, int]
    candidates_evaluated: int
    current: CandidateMetrics | None = None
    change_recommended: bool | None = None
    excluded_vehicles: list[str] = field(default_factory=list)
