"""data/drive_spots.csv 로더. 목적지 없는 드라이브에서 고를 수 있는 명소 목록이다.

좌표 대부분은 OpenStreetMap(Nominatim) 검색으로 보정했다(© OpenStreetMap contributors, ODbL).
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from ..optimization.types import LatLon


class DriveTheme(str, Enum):
    SEA = "SEA"
    FOREST = "FOREST"
    NIGHT = "NIGHT"
    LAKE = "LAKE"
    RANDOM = "RANDOM"

    @property
    def label(self) -> str:
        return THEME_LABELS[self]


THEME_LABELS = {
    DriveTheme.SEA: "바다",
    DriveTheme.FOREST: "숲길",
    DriveTheme.NIGHT: "야경",
    DriveTheme.LAKE: "호수·강",
    DriveTheme.RANDOM: "완전 랜덤",
}


@dataclass(frozen=True)
class DriveSpot:
    id: str
    name: str
    themes: frozenset[DriveTheme]
    region: str
    position: LatLon
    description: str

    def matches(self, theme: DriveTheme) -> bool:
        return theme is DriveTheme.RANDOM or theme in self.themes


def _parse(row: dict[str, str]) -> DriveSpot:
    return DriveSpot(
        id=row["id"],
        name=row["name"],
        themes=frozenset(DriveTheme(value) for value in row["themes"].split("|") if value),
        region=row["region"],
        position=LatLon(float(row["lat"]), float(row["lon"])),
        description=row["description"],
    )


def load_drive_spots(path: Path) -> list[DriveSpot]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as file:
        return [_parse(row) for row in csv.DictReader(file)]
