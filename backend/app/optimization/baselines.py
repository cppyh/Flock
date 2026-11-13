"""알고리즘 평가용 기준선(design.md §8.6). 모든 방식은 같은 경로 조회 결과로 비교한다."""

from __future__ import annotations

from dataclasses import dataclass, replace

from ..integrations.routing.base import MeteredRouting, Route, RoutingProvider
from .candidates import FACILITY_EXCLUDED_KINDS, matches_direction
from .engine import RecommendationEngine
from .geo import RouteLine, haversine_m, midpoint
from .scoring import score_candidates
from .types import (
    Candidate,
    CandidateMetrics,
    EngineConfig,
    MeetingPurpose,
    RecommendationRequest,
    RestArea,
)
from .windows import meet_window

ROUTE_MIDDLE = 0.5


@dataclass(frozen=True)
class BaselineResult:
    method: str
    metrics: CandidateMetrics | None
    failed_reason: str | None = None


def _as_candidate(area: RestArea) -> Candidate:
    return Candidate(area.id, area.name, area.position, area)


class BaselineEvaluator:
    def __init__(
        self,
        routing: RoutingProvider,
        rest_areas: list[RestArea],
        config: EngineConfig,
    ):
        self._routing = routing
        self._areas = [a for a in rest_areas if a.kind not in FACILITY_EXCLUDED_KINDS]
        self._all_areas = rest_areas
        self._config = config
        self._engine = RecommendationEngine(routing, rest_areas, config)

    def evaluate(self, request: RecommendationRequest) -> list[BaselineResult]:
        routing = MeteredRouting(self._routing)
        routes = self._engine.base_routes(request, routing)
        return [
            BaselineResult("B0 목적지 직행", self._engine.destination_metrics(request, routes)),
            self._single("B1 지리적 중간점", self._midpoint_area(request), request, routes, routing),
            self._single("B2 대표 차량 경로", self._representative_area(request, routes), request, routes, routing),
            self._without_meeting_gain(request),
            self._ours(request),
        ]

    def _single(
        self,
        method: str,
        area: RestArea | None,
        request: RecommendationRequest,
        routes: dict[str, Route],
        routing: RoutingProvider,
    ) -> BaselineResult:
        if area is None:
            return BaselineResult(method, None, "후보 없음")
        metrics = self._engine.evaluate_candidates(request, routes, routing, [_as_candidate(area)])
        if not metrics:
            return BaselineResult(method, None, "일부 차량 접근 불가")
        return BaselineResult(method, metrics[0])

    def _midpoint_area(self, request: RecommendationRequest) -> RestArea | None:
        center = midpoint([vehicle.position for vehicle in request.vehicles])
        return min(self._areas, key=lambda area: haversine_m(center, area.position), default=None)

    def _representative_area(
        self, request: RecommendationRequest, routes: dict[str, Route]
    ) -> RestArea | None:
        """대표 차량(첫 번째)의 경로만 보고, 그 경로 중간에 가장 가까운 휴게소를 고른다."""
        line = RouteLine(routes[request.vehicles[0].vehicle_id].polyline)
        best: RestArea | None = None
        best_offset = float("inf")
        for area in self._areas:
            location = line.locate(area.position)
            if location.distance_m > self._config.candidate_buffer_m:
                continue
            if not matches_direction(area, location):
                continue
            offset = abs(location.fraction - ROUTE_MIDDLE)
            if offset < best_offset:
                best, best_offset = area, offset
        return best

    def _without_meeting_gain(self, request: RecommendationRequest) -> BaselineResult:
        """B3: 동행 보상과 시간대 제약을 뺐을 때 결과가 어떻게 달라지는지 본다."""
        unconstrained = replace(request, purpose=MeetingPurpose.ANY)
        routing = MeteredRouting(self._routing)
        routes = self._engine.base_routes(unconstrained, routing)
        evaluation = self._engine._evaluate(unconstrained, routes, routing, None, self._config.top_k)
        ranked = score_candidates(
            [*evaluation.eligible, evaluation.destination],
            self._config,
            MeetingPurpose.ANY,
            include_shared=False,
        )
        return BaselineResult("B3 동행·시간대 제외", ranked[0].metrics)

    def _ours(self, request: RecommendationRequest) -> BaselineResult:
        result = self._engine.recommend(request)
        return BaselineResult("Ours", result.top[0].metrics)


def in_window(request: RecommendationRequest, config: EngineConfig, metrics: CandidateMetrics) -> bool:
    window = meet_window(request, config)
    return window is None or window.contains(metrics.meet_time)
