"""data/rest_areas.csv 로더."""

from __future__ import annotations

import csv
from pathlib import Path

from ..optimization.types import LatLon, RestArea


def _flag(value: str) -> bool:
    return value.strip().upper() == "Y"


def _parse(row: dict[str, str]) -> RestArea:
    toward_point = (
        LatLon(float(row["toward_lat"]), float(row["toward_lon"])) if row["toward_lat"] else None
    )
    return RestArea(
        id=row["id"],
        name=row["name"],
        route_name=row["route_name"],
        position=LatLon(float(row["lat"]), float(row["lon"])),
        kind=row["kind"],
        bidirectional=_flag(row["bidirectional"]),
        toward=row["toward"] or None,
        toward_point=toward_point,
        open_start=row["open_start"],
        open_end=row["open_end"],
        parking_spaces=int(row["parking_spaces"]) if row["parking_spaces"] else None,
        has_restaurant=_flag(row["has_restaurant"]),
        has_ev_charger=_flag(row["has_ev_charger"]),
        has_gas_station=_flag(row["has_gas_station"]),
        has_toilet=_flag(row["has_toilet"]),
        has_store=_flag(row["has_store"]),
        data_date=row["data_date"],
    )


def load_rest_areas(path: Path) -> list[RestArea]:
    with path.open(encoding="utf-8", newline="") as file:
        return [_parse(row) for row in csv.DictReader(file)]
