"""목적지 없는 그룹 드라이브의 코스 고르기.

정한 시간(왕복 기준, 1~5시간)에서 ±허용 오차 안에 드는 명소 중 하나를 무작위로 고른다.
경로 조회 전에 직선거리로 후보를 줄이고, 실제 왕복 시간은 차량별 도로 경로 시간의 평균으로 계산한다.
"""

from __future__ import annotations

import random
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from ..integrations.drive_spots import DriveSpot, DriveTheme
from ..integrations.routing.base import Matrix
from ..optimization.geo import haversine_m
from ..optimization.types import LatLon

# 직선거리로 도로 시간을 어림할 때 쓰는 값. 후보를 줄이는 데만 쓰고 결과는 실제 경로 시간으로 판단한다.
ROAD_FACTOR = 1.3
ESTIMATE_SPEED_KMH = 70
PREFILTER_LIMIT = 40
HISTORY_LIMIT = 20

KST = timezone(timedelta(hours=9))
# 서울 기준 월별 대략적인 해 지는 시각(시, 분). 야경 코스 안내에만 쓴다.
SUNSET_KST = {
    1: (17, 40), 2: (18, 10), 3: (18, 40), 4: (19, 5), 5: (19, 30), 6: (19, 55),
    7: (19, 55), 8: (19, 30), 9: (18, 50), 10: (18, 10), 11: (17, 30), 12: (17, 20),
}


@dataclass(frozen=True)
class DrivePick:
    spot: DriveSpot
    round_trip_minutes: float
    one_way_minutes: dict[str, float]
    within_tolerance: bool
    in_window: int

    @property
    def mean_one_way_minutes(self) -> float:
        return self.round_trip_minutes / 2


def _centroid(points: Sequence[LatLon]) -> LatLon:
    return LatLon(sum(p.lat for p in points) / len(points), sum(p.lon for p in points) / len(points))


def estimated_round_trip_minutes(origin: LatLon, spot: DriveSpot) -> float:
    km = haversine_m(origin, spot.position) / 1000 * ROAD_FACTOR
    return 2 * km / ESTIMATE_SPEED_KMH * 60


def prefilter(
    spots: Sequence[DriveSpot],
    theme: DriveTheme,
    origins: Sequence[LatLon],
    target_minutes: float,
    limit: int = PREFILTER_LIMIT,
) -> list[DriveSpot]:
    """경로 조회량을 줄이려고 어림 왕복 시간이 목표에 가까운 순으로 자른다."""
    center = _centroid(origins)
    matching = [spot for spot in spots if spot.matches(theme)]
    matching.sort(key=lambda spot: (abs(estimated_round_trip_minutes(center, spot) - target_minutes), spot.id))
    return matching[:limit]


def choose(
    spots: Sequence[DriveSpot],
    vehicle_ids: Sequence[str],
    durations: Matrix,
    target_minutes: float,
    tolerance_minutes: float,
    history: Sequence[str],
    rng: random.Random,
) -> DrivePick | None:
    """durations는 차량(행) × 명소(열)의 편도 주행 시간(초)이다."""
    measured: list[DrivePick] = []
    for column, spot in enumerate(spots):
        seconds = [durations[row][column] for row in range(len(vehicle_ids))]
        if any(value is None for value in seconds):
            continue
        one_way = {vid: value / 60 for vid, value in zip(vehicle_ids, seconds)}
        round_trip = 2 * sum(one_way.values()) / len(one_way)
        within = abs(round_trip - target_minutes) <= tolerance_minutes
        measured.append(DrivePick(spot, round_trip, one_way, within, 0))
    if not measured:
        return None

    window = [item for item in measured if item.within_tolerance]
    seen = set(history)
    fresh = [item for item in measured if item.spot.id not in seen]
    fresh_window = [item for item in fresh if item.within_tolerance]

    def closest(items: list[DrivePick]) -> DrivePick:
        return min(items, key=lambda item: (abs(item.round_trip_minutes - target_minutes), item.spot.id))

    if fresh_window:
        pick = rng.choice(fresh_window)
    elif fresh:
        # 범위 안을 다 보여 줬으면 같은 곳을 되풀이하지 않고, 아직 안 보여 준 곳 중 시간이 가장 가까운 곳을 고른다.
        pick = closest(fresh)
    elif window:
        # 후보를 모두 보여 줬다. 다시 섞되 바로 직전 코스는 피한다.
        last = history[-1] if history else None
        pick = rng.choice([item for item in window if item.spot.id != last] or window)
    else:
        pick = closest(measured)
    return DrivePick(pick.spot, pick.round_trip_minutes, pick.one_way_minutes, pick.within_tolerance, len(window))


def format_duration(minutes: float) -> str:
    rounded = int(round(minutes / 5) * 5) if minutes >= 30 else int(round(minutes))
    hours, rest = divmod(rounded, 60)
    if hours and rest:
        return f"{hours}시간 {rest}분"
    return f"{hours}시간" if hours else f"{rest}분"


def drive_notice(pick: DrivePick, theme: DriveTheme, hours: int, now: datetime) -> str | None:
    """운전 중에 읽을 수 있도록 한 문장으로 쓴다."""
    if not pick.within_tolerance:
        label = "" if theme is DriveTheme.RANDOM else f"{theme.label} "
        trip = f"왕복 약 {format_duration(pick.round_trip_minutes)}"
        if pick.in_window:
            return f"{hours}시간에 맞는 {label}코스를 모두 보여 드려 시간이 가장 가까운 다른 곳({trip})을 골랐어요"
        return f"{hours}시간에 맞는 {label}코스가 없어 가장 가까운 곳({trip})을 골랐어요"
    if theme is DriveTheme.NIGHT or (theme is DriveTheme.RANDOM and DriveTheme.NIGHT in pick.spot.themes and len(pick.spot.themes) == 1):
        arrival = (now + timedelta(minutes=pick.mean_one_way_minutes)).astimezone(KST)
        hour, minute = SUNSET_KST[arrival.month]
        sunset = arrival.replace(hour=hour, minute=minute, second=0, microsecond=0)
        sunrise = arrival.replace(hour=6, minute=30, second=0, microsecond=0)
        if sunrise <= arrival < sunset:
            return f"도착 예상 {arrival:%H:%M}, 야경은 해가 진 뒤(약 {sunset:%H:%M})가 좋아요"
    return None
