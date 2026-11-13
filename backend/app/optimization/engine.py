"""합류 지점 추천 엔진. 후보 생성 → 경로 조회 → 필터 → 점수 → TOP 3 순서로 진행한다."""

from __future__ import annotations

import time
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from ..integrations.routing.base import MeteredRouting, Route, RoutingProvider
from .candidates import select_candidates
from .scoring import compute_metrics, score_candidates
from .types import (
    Candidate,
    CandidateMetrics,
    EngineConfig,
    MeetWindow,
    RecommendationRequest,
    RecommendationResult,
    RestArea,
    ScoredCandidate,
)
from .windows import meet_window

TOP_N = 3
DESTINATION_ID = "destination"
DESTINATION_NOTICE = "이번 경로는 중간 합류의 이득이 작아 목적지에서 만나는 것을 추천합니다"
KEEP_CURRENT_NOTICE = "현재 합류 장소를 유지하는 것이 좋습니다"
AFTER_ROUTE_TTL_SECONDS = 6 * 3600
KST = timezone(timedelta(hours=9))


def is_open_at(area: RestArea, moment: datetime) -> bool:
    """공공데이터의 운영 시각 기준. 시각은 현지(KST) 시계 기준 문자열이다."""
    if not area.open_start or not area.open_end:
        return True
    local = moment.astimezone(KST).strftime("%H:%M")
    if area.open_start <= area.open_end:
        return area.open_start <= local <= area.open_end
    return local >= area.open_start or local <= area.open_end


@dataclass(frozen=True)
class Evaluation:
    """한 시간대 단계의 계산 결과."""

    metrics: list[CandidateMetrics]
    destination: CandidateMetrics
    eligible: list[CandidateMetrics]
    rejected: Counter[str]
    current: CandidateMetrics | None
    current_usable: bool


class RecommendationEngine:
    def __init__(self, routing: RoutingProvider, rest_areas: list[RestArea], config: EngineConfig):
        self._routing = routing
        self._rest_areas = rest_areas
        self._config = config

    def recommend(self, request: RecommendationRequest, top_k: int | None = None) -> RecommendationResult:
        started = time.perf_counter()
        routing = MeteredRouting(self._routing)
        k = top_k or self._config.top_k
        routes = self.base_routes(request, routing)

        window = meet_window(request, self._config)
        evaluation = self._evaluate(request, routes, routing, window, k)
        expanded = False
        if window is not None and not evaluation.eligible:
            window = meet_window(request, self._config, self._config.window_expansion_minutes)
            evaluation = self._evaluate(request, routes, routing, window, k)
            expanded = True

        ranked = score_candidates(
            [*evaluation.eligible, evaluation.destination], self._config, request.purpose
        )
        top = ranked[:TOP_N]
        destination = next(item for item in ranked if item.metrics.candidate.is_destination)
        notice = DESTINATION_NOTICE if top[0].metrics.candidate.is_destination else None

        change_recommended = None
        if request.current_place_id is not None:
            # 더 갈 수 없는 합류 지점은 개선 폭과 상관없이 유지를 권하면 안 된다.
            change_recommended = not evaluation.current_usable or self._improves_on_current(top[0], evaluation.current)
            if not change_recommended:
                notice = KEEP_CURRENT_NOTICE

        return RecommendationResult(
            top=top,
            destination=destination,
            notice=notice,
            window=window,
            window_expanded=expanded,
            computed_at=request.now,
            elapsed_seconds=time.perf_counter() - started,
            route_source=routing.source,
            traffic_aware=routing.traffic_aware,
            usage=routing.usage,
            rejected=dict(evaluation.rejected),
            candidates_evaluated=len(evaluation.metrics),
            current=evaluation.current,
            change_recommended=change_recommended,
        )

    def base_routes(self, request: RecommendationRequest, routing: RoutingProvider) -> dict[str, Route]:
        destination = request.destination.position
        return {v.vehicle_id: routing.route(v.position, destination) for v in request.vehicles}

    def evaluate_candidates(
        self,
        request: RecommendationRequest,
        routes: dict[str, Route],
        routing: RoutingProvider,
        candidates: list[Candidate],
    ) -> list[CandidateMetrics]:
        """주어진 후보의 경로 시간을 조회해 지표를 계산한다. 접근 불가 후보는 빠진다."""
        if not candidates:
            return []
        origins = [vehicle.position for vehicle in request.vehicles]
        targets = [candidate.position for candidate in candidates]
        to_matrix = routing.matrix(origins, targets)
        after_matrix = routing.matrix(
            targets, [request.destination.position], ttl_seconds=AFTER_ROUTE_TTL_SECONDS
        )
        base_seconds = {vehicle_id: route.duration_seconds for vehicle_id, route in routes.items()}

        metrics = []
        for column, candidate in enumerate(candidates):
            to_seconds = {
                vehicle.vehicle_id: to_matrix[row][column]
                for row, vehicle in enumerate(request.vehicles)
            }
            after_seconds = after_matrix[column][0]
            if after_seconds is None or any(value is None for value in to_seconds.values()):
                continue
            metrics.append(
                compute_metrics(
                    candidate, request.vehicles, to_seconds, after_seconds, base_seconds, self._config.ev
                )
            )
        return metrics

    def destination_metrics(
        self, request: RecommendationRequest, routes: dict[str, Route]
    ) -> CandidateMetrics:
        base_seconds = {vehicle_id: route.duration_seconds for vehicle_id, route in routes.items()}
        candidate = Candidate(DESTINATION_ID, request.destination.name, request.destination.position)
        return compute_metrics(candidate, request.vehicles, base_seconds, 0.0, base_seconds, self._config.ev)

    def _evaluate(
        self,
        request: RecommendationRequest,
        routes: dict[str, Route],
        routing: RoutingProvider,
        window: MeetWindow | None,
        top_k: int,
    ) -> Evaluation:
        selection = select_candidates(self._rest_areas, routes, request, window, self._config, top_k)
        areas = list(selection.selected)
        current_area = self._current_area(request)
        if current_area is not None and current_area not in areas:
            areas.append(current_area)

        candidates = [Candidate(area.id, area.name, area.position, area) for area in areas]
        metrics = self.evaluate_candidates(request, routes, routing, candidates)
        rejected = Counter(selection.rejected)
        rejected["PARTIAL_ACCESS"] += len(candidates) - len(metrics)

        eligible = []
        for item in metrics:
            # 현재 합류 지점은 비교하려고 지표만 계산한다. 조건을 통과해야 추천 대상이 된다.
            if item.candidate.id not in selection.accessible_ids:
                continue
            reason = self._rejection(item, window)
            if reason:
                rejected[reason] += 1
            else:
                eligible.append(item)

        destination = self.destination_metrics(request, routes)
        current = None
        current_usable = False
        if request.current_place_id == DESTINATION_ID:
            current = destination
            current_usable = True
        elif request.current_place_id is not None:
            current = next(
                (m for m in metrics if m.candidate.id == request.current_place_id), None
            )
            current_usable = any(m.candidate.id == request.current_place_id for m in eligible)
        return Evaluation(metrics, destination, eligible, +rejected, current, current_usable)

    def _current_area(self, request: RecommendationRequest) -> RestArea | None:
        return next((a for a in self._rest_areas if a.id == request.current_place_id), None)

    def _rejection(self, item: CandidateMetrics, window: MeetWindow | None) -> str | None:
        area = item.candidate.rest_area
        if area is not None and not is_open_at(area, item.meet_time):
            return "CLOSED"
        if item.max_detour_minutes > self._config.detour_max_minutes:
            return "DETOUR_LIMIT"
        if window is not None and not window.contains(item.meet_time):
            return "OUTSIDE_WINDOW"
        return None

    def _improves_on_current(self, best: ScoredCandidate, current: CandidateMetrics) -> bool:
        """추천 장소가 자주 바뀌면 혼란스러우므로 충분히 나아질 때만 변경을 제안한다."""
        if best.metrics.candidate.id == current.candidate.id:
            return False
        improvement = current.group_delay_minutes - best.metrics.group_delay_minutes
        return improvement >= self._config.reroute_min_improvement_minutes
