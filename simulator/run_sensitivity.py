"""가중치 민감도 분석. 경로 조회는 시나리오마다 한 번만 하고 가중치만 바꿔 다시 점수를 낸다.

실행: backend/.venv/Scripts/python simulator/run_sensitivity.py
"""
import json
import pathlib
import sys
from dataclasses import replace
from datetime import datetime, timezone

REPO = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "backend"))
sys.path.insert(0, str(REPO / "simulator"))
sys.stdout.reconfigure(encoding="utf-8")

from app.config import Settings
from app.integrations.rest_areas import load_rest_areas
from app.integrations.routing import build_routing
from app.integrations.routing.base import MeteredRouting
from app.main import engine_config
from app.optimization.engine import RecommendationEngine
from app.optimization.scoring import score_candidates
from app.optimization.types import MeetingPurpose
from app.optimization.windows import meet_window
from run_experiments import PURPOSES, SCENARIOS, build_request

settings = Settings(_env_file=None, routing_provider="osrm")
routing = build_routing(settings)
config = engine_config(settings)
engine = RecommendationEngine(routing, load_rest_areas(settings.rest_areas_csv), config)
scenarios = json.loads(SCENARIOS.read_text(encoding="utf-8"))["scenarios"]
now = datetime.now(timezone.utc).replace(microsecond=0)

cases = []
for scenario in scenarios:
    for purpose in PURPOSES:
        request = build_request(scenario, purpose, now)
        metered = MeteredRouting(routing)
        routes = engine.base_routes(request, metered)
        window = meet_window(request, config)
        evaluation = engine._evaluate(request, routes, metered, window, config.top_k)
        if window is not None and not evaluation.eligible:
            window = meet_window(request, config, config.window_expansion_minutes)
            evaluation = engine._evaluate(request, routes, metered, window, config.top_k)
        cases.append((scenario["name"], purpose, evaluation))
        print("수집", scenario["name"], purpose.value, len(evaluation.eligible), flush=True)


def fixed_scale_winner(evaluation, purpose):
    """min-max 대신 고정 척도로 절대 크기를 살린다: 시간 항목을 60분(최대 우회는 20분) 기준으로 나눈다."""
    epsilon = config.epsilon_for(purpose)

    def score(m):
        return (
            config.alpha * m.total_detour_minutes / 60
            + config.beta * m.total_wait_minutes / 60
            + config.gamma * m.max_detour_minutes / 20
            + config.delta * m.penalty
            - epsilon * m.shared_minutes / 60
        )

    return min([*evaluation.eligible, evaluation.destination], key=score)


def summarize(label, winner_of):
    winners = [(name, purpose, winner_of(evaluation, purpose)) for name, purpose, evaluation in cases]
    middle = [m for _, _, m in winners if not m.candidate.is_destination]
    no_candidate = sum(1 for _, _, e in cases if not e.eligible)
    avg_detour = sum(m.total_detour_minutes for _, _, m in winners) / len(winners)
    max_detour = max(m.max_detour_minutes for _, _, m in winners)
    avg_shared = sum(m.shared_minutes for _, _, m in winners) / len(winners)
    print(f"| {label} | {len(winners) - len(middle)}/{len(winners)} | {len(middle)}/{len(winners)} | "
          f"{avg_detour:.1f} | {max_detour:.1f} | {avg_shared:.0f} |", flush=True)
    return no_candidate


print("\n| 설정 | 목적지 1위 | 중간 합류 1위 | 총 우회 평균 | 최대 개인 우회 | 동행 평균 |")
print("|---|---|---|---|---|---|")
for epsilon in (0.6, 1.0, 1.5, 2.0, 3.0):
    variant = replace(config, epsilon=epsilon, epsilon_depart_together=epsilon * 2)

    def winner(evaluation, purpose, variant=variant):
        ranked = score_candidates([*evaluation.eligible, evaluation.destination], variant, purpose)
        return ranked[0].metrics

    none = summarize(f"min-max, ε={epsilon}", winner)
summarize("고정 척도, ε=0.6", fixed_scale_winner)
print(f"\n시간대·우회 필터를 통과한 중간 후보가 아예 없는 경우: {none}/{len(cases)}")
