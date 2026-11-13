"""만남 목적별 합류 시간대. design.md §8.3의 표를 코드로 옮긴 것이다."""

from __future__ import annotations

from datetime import timedelta

from .types import EngineConfig, MeetingPurpose, MeetWindow, RecommendationRequest


def meet_window(
    request: RecommendationRequest,
    config: EngineConfig,
    expansion_minutes: float = 0,
) -> MeetWindow | None:
    """합류 완료 시각의 허용 범위를 돌려준다. 제약이 없으면 None이다."""
    expansion = timedelta(minutes=expansion_minutes)
    if request.purpose is MeetingPurpose.DEPART_TOGETHER:
        end = request.now + timedelta(minutes=config.depart_together_window_minutes)
        return MeetWindow(start=None, end=end + expansion)
    if request.purpose is MeetingPurpose.REST:
        start = request.departure_at + timedelta(minutes=config.rest_window_start_minutes)
        end = request.departure_at + timedelta(minutes=config.rest_window_end_minutes)
        return MeetWindow(start=start - expansion, end=end + expansion)
    if request.purpose is MeetingPurpose.MEAL:
        if request.meal_time is None:
            raise ValueError("식사 목적에는 식사 시각이 필요합니다.")
        margin = timedelta(minutes=config.meal_window_minutes)
        return MeetWindow(start=request.meal_time - margin - expansion, end=request.meal_time + margin + expansion)
    return None
