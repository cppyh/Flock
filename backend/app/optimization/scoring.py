"""후보별 지표 계산과 정규화 목적함수(design.md §8.3)."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime, timedelta

from .ev import EvConfig, plan_ev
from .types import (
    Candidate,
    CandidateMetrics,
    EngineConfig,
    MeetingPurpose,
    RestArea,
    ScoredCandidate,
    VehicleInput,
)

# 장소 점수는 시간 항목이 아니므로 3분 기준 대신 이 값보다 작은 차이를 무시한다.
PENALTY_MIN_RANGE = 0.05

FULL_PARKING_SPACES = 200
UNKNOWN_PARKING_SCORE = 0.3


def suitability_penalty(area: RestArea | None) -> tuple[float, bool]:
    """장소 부적합도(0~1)와 데이터 불확실 여부. 공통 목적지는 어차피 가는 곳이므로 0이다."""
    if area is None:
        return 0.0, False
    uncertain = area.parking_spaces is None
    parking = (
        UNKNOWN_PARKING_SCORE
        if area.parking_spaces is None
        else min(area.parking_spaces / FULL_PARKING_SPACES, 1.0)
    )
    score = (
        0.5 * parking
        + 0.2 * area.has_restaurant
        + 0.15 * area.has_ev_charger
        + 0.1 * area.has_toilet
        + 0.05 * area.has_store
    )
    return round(1 - score, 4), uncertain


def compute_metrics(
    candidate: Candidate,
    vehicles: Sequence[VehicleInput],
    to_seconds: dict[str, float],
    after_seconds: float,
    base_seconds: dict[str, float],
    ev_config: EvConfig | None = None,
) -> CandidateMetrics:
    """측정 시각이 다른 위치를 공정하게 비교하려고 도착 시각을 절대 시각으로 환산한다."""
    arrivals: dict[str, datetime] = {
        vehicle.vehicle_id: vehicle.measured_at + timedelta(seconds=to_seconds[vehicle.vehicle_id])
        for vehicle in vehicles
    }
    meet_time = max(arrivals.values())
    detours = {
        vehicle_id: max(0.0, to_seconds[vehicle_id] + after_seconds - base_seconds[vehicle_id]) / 60
        for vehicle_id in arrivals
    }
    waits = {
        vehicle_id: (meet_time - arrival).total_seconds() / 60
        for vehicle_id, arrival in arrivals.items()
    }
    penalty, uncertain = suitability_penalty(candidate.rest_area)
    ev_config = ev_config or EvConfig()
    has_charger = candidate.rest_area is not None and candidate.rest_area.has_ev_charger
    ev_plans = {
        vehicle.vehicle_id: plan_ev(
            vehicle.battery_percent,
            to_seconds[vehicle.vehicle_id],
            after_seconds,
            waits[vehicle.vehicle_id],
            has_charger,
            ev_config,
        )
        for vehicle in vehicles
        if vehicle.battery_percent is not None
    }
    return CandidateMetrics(
        candidate=candidate,
        to_seconds=dict(to_seconds),
        after_seconds=after_seconds,
        arrivals=arrivals,
        meet_time=meet_time,
        detour_minutes=detours,
        wait_minutes=waits,
        total_detour_minutes=sum(detours.values()),
        total_wait_minutes=sum(waits.values()),
        max_detour_minutes=max(detours.values()),
        max_wait_minutes=max(waits.values()),
        shared_minutes=after_seconds / 60,
        penalty=penalty,
        data_uncertain=uncertain,
        ev_plans=ev_plans,
    )


def normalize(values: Sequence[float], min_range: float) -> list[float]:
    """후보 집합 기준 min-max 정규화. 차이가 의미 없을 만큼 작으면 모두 0으로 본다."""
    if not values:
        return []
    low, high = min(values), max(values)
    if high - low < min_range:
        return [0.0] * len(values)
    return [(value - low) / (high - low) for value in values]


def score_candidates(
    metrics: Sequence[CandidateMetrics],
    config: EngineConfig,
    purpose: MeetingPurpose,
    include_shared: bool = True,
) -> list[ScoredCandidate]:
    """점수가 작을수록 좋다. 동점이면 그룹 전체 지연이 작은 후보를 앞에 둔다.

    전기차가 있으면 충전으로 쓴 대기는 대기 항목에서 빼고, 충전 때문에 더 기다리는 시간은 따로 더한다.
    배터리 정보가 없으면 두 값 모두 0이라 기존 점수와 같다.
    """
    time_range = config.normalize_min_range_minutes
    detour = normalize([m.total_detour_minutes for m in metrics], time_range)
    wait = normalize([m.effective_wait_minutes for m in metrics], time_range)
    ev_delay = normalize([m.ev_extra_minutes for m in metrics], time_range)
    worst = normalize([m.max_detour_minutes for m in metrics], time_range)
    penalty = normalize([m.penalty for m in metrics], PENALTY_MIN_RANGE)
    shared = normalize([m.shared_minutes for m in metrics], time_range)
    epsilon = config.epsilon_for(purpose) if include_shared else 0.0

    scored = [
        ScoredCandidate(
            metrics=item,
            score=(
                config.alpha * detour[index]
                + config.beta * wait[index]
                + config.gamma * worst[index]
                + config.delta * penalty[index]
                + config.zeta * ev_delay[index]
                - epsilon * shared[index]
            ),
        )
        for index, item in enumerate(metrics)
    ]
    return sorted(scored, key=lambda s: (s.score, s.metrics.group_delay_minutes, s.metrics.candidate.id))
