"""기준선 비교 실험(design.md §8.6, 기획서 §15.3).

각 시나리오에서 B0·B1·B2·B3·Ours를 같은 경로 제공자로 계산해 지표를 비교하고
결과를 docs/experiments-results.md로 저장한다.

실행: backend/.venv/Scripts/python simulator/run_experiments.py [--provider osrm|estimate]
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from app.config import Settings, engine_config  # noqa: E402
from app.integrations.rest_areas import load_rest_areas  # noqa: E402
from app.integrations.routing import build_routing  # noqa: E402
from app.optimization.baselines import BaselineEvaluator, BaselineResult  # noqa: E402
from app.optimization.types import (  # noqa: E402
    Destination,
    LatLon,
    MeetingPurpose,
    RecommendationRequest,
    VehicleInput,
)

SCENARIOS = REPO_ROOT / "simulator" / "scenarios" / "experiments.json"
RESULTS = REPO_ROOT / "docs" / "experiments-results.md"
PURPOSES = (MeetingPurpose.ANY, MeetingPurpose.REST)


def build_request(scenario: dict, purpose: MeetingPurpose, now: datetime) -> RecommendationRequest:
    target = scenario["destination"]
    vehicles = tuple(
        VehicleInput(f"차량{index + 1}", LatLon(lat, lon), now)
        for index, (lat, lon) in enumerate(scenario["vehicles"])
    )
    return RecommendationRequest(
        vehicles=vehicles,
        destination=Destination(target["name"], LatLon(target["lat"], target["lon"])),
        purpose=purpose,
        now=now,
        departure_at=now,
    )


def row_text(scenario_name: str, purpose: MeetingPurpose, row: BaselineResult, now: datetime) -> str:
    if row.metrics is None:
        return f"| {scenario_name} | {purpose.value} | {row.method} | 실패: {row.failed_reason} | - | - | - | - | - |"
    m = row.metrics
    meet_minutes = (m.meet_time - now).total_seconds() / 60
    return (
        f"| {scenario_name} | {purpose.value} | {row.method} | {m.candidate.name} | "
        f"{m.total_detour_minutes:.1f} | {m.total_wait_minutes:.1f} | {m.max_detour_minutes:.1f} | "
        f"{meet_minutes:.0f} | {m.shared_minutes:.0f} |"
    )


def summary_lines(totals: dict[str, list[BaselineResult]]) -> list[str]:
    lines = [
        "| 방식 | 성공/전체 | 총 우회 평균(분) | 총 대기 평균(분) | 최대 우회 평균(분) | 합류 후 동행 평균(분) |",
        "|---|---|---|---|---|---|",
    ]
    for method, rows in totals.items():
        done = [row.metrics for row in rows if row.metrics is not None]
        if not done:
            lines.append(f"| {method} | 0/{len(rows)} | - | - | - | - |")
            continue
        lines.append(
            f"| {method} | {len(done)}/{len(rows)} | "
            f"{statistics.mean(m.total_detour_minutes for m in done):.1f} | "
            f"{statistics.mean(m.total_wait_minutes for m in done):.1f} | "
            f"{statistics.mean(m.max_detour_minutes for m in done):.1f} | "
            f"{statistics.mean(m.shared_minutes for m in done):.1f} |"
        )
    return lines


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", default="osrm", choices=["osrm", "estimate"])
    args = parser.parse_args()

    settings = Settings(_env_file=None, routing_provider=args.provider)
    routing = build_routing(settings)
    config = engine_config(settings)
    evaluator = BaselineEvaluator(routing, load_rest_areas(settings.rest_areas_csv), config)
    scenarios = json.loads(SCENARIOS.read_text(encoding="utf-8"))["scenarios"]

    now = datetime.now(timezone.utc).replace(microsecond=0)
    detail = []
    totals: dict[str, list[BaselineResult]] = defaultdict(list)
    started = time.perf_counter()
    for scenario in scenarios:
        for purpose in PURPOSES:
            request = build_request(scenario, purpose, now)
            for row in evaluator.evaluate(request):
                totals[row.method].append(row)
                detail.append(row_text(scenario["name"], purpose, row, now))
            print(f"완료: {scenario['name']} / {purpose.value}", flush=True)

    lines = [
        "# 기준선 비교 실험 결과",
        "",
        f"- 실행 시각: {now.isoformat()}",
        f"- 경로 제공자: {routing.source} (실시간 교통 미반영)",
        f"- 시나리오: {len(scenarios)}개 × 만남 목적 {len(PURPOSES)}종, 합계 {time.perf_counter() - started:.0f}초",
        "- 가중치: 초기값(design.md §8.3). 민감도 분석 전 값이다.",
        "",
        "자동 생성 파일입니다. `simulator/run_experiments.py`로 다시 만드세요.",
        "",
        "## 요약",
        "",
        *summary_lines(totals),
        "",
        "## 시나리오별 결과",
        "",
        "| 시나리오 | 목적 | 방식 | 합류 장소 | 총 우회(분) | 총 대기(분) | 최대 우회(분) | 합류까지(분) | 동행(분) |",
        "|---|---|---|---|---|---|---|---|---|",
        *detail,
        "",
    ]
    RESULTS.write_text("\n".join(lines), encoding="utf-8")
    print(f"{RESULTS} 저장")


if __name__ == "__main__":
    main()
