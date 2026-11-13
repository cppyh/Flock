"""drive_spots_seed.csv의 대략 좌표를 OpenStreetMap(Nominatim) 검색 결과로 보정해 drive_spots.csv를 만든다.

- Nominatim 이용 정책에 따라 1초에 한 번만 요청하고, 결과는 파일로 남겨 다시 요청하지 않는다.
- 대략 좌표에서 MAX_SHIFT_KM 안에 있는 검색 결과만 받아들인다. 없으면 대략 좌표를 두고 source를 APPROX로 표시한다.
- 보정한 좌표는 OpenStreetMap 데이터이므로 © OpenStreetMap contributors(ODbL)를 함께 밝힌다.
- 지금의 drive_spots.csv는 이 결과를 사람이 검토해 고친 것이다(호수 중심 좌표를 댐·공원 입구로 옮기는 등).
  다시 실행하면 source가 OSM인 행은 그대로 두고 나머지만 다시 찾는다.

실행: python data/build_drive_spots.py
"""

from __future__ import annotations

import csv
import math
import sys
import time
from pathlib import Path

import httpx

HERE = Path(__file__).resolve().parent
SEED = HERE / "drive_spots_seed.csv"
OUTPUT = HERE / "drive_spots.csv"
ENDPOINT = "https://nominatim.openstreetmap.org/search"
USER_AGENT = "MeetRoute-dataprep/0.1 (contest prototype; one-off geocoding of public landmarks)"
MAX_SHIFT_KM = 5.0
FIELDS = ["id", "name", "themes", "region", "lat", "lon", "description", "source"]


def distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(a))


def search(client: httpx.Client, query: str) -> list[dict]:
    response = client.get(
        ENDPOINT,
        params={"q": query, "format": "jsonv2", "countrycodes": "kr", "limit": 5, "accept-language": "ko"},
    )
    response.raise_for_status()
    return response.json()


def main() -> int:
    rows = list(csv.DictReader(SEED.open(encoding="utf-8")))
    done = {}
    if OUTPUT.exists():
        done = {row["id"]: row for row in csv.DictReader(OUTPUT.open(encoding="utf-8")) if row["source"] == "OSM"}

    output, review = [], []
    with httpx.Client(headers={"User-Agent": USER_AGENT}, timeout=20) as client:
        for row in rows:
            approx = float(row["approx_lat"]), float(row["approx_lon"])
            if row["id"] in done:
                output.append(done[row["id"]])
                continue
            best = None
            for query in (row["query"], f"{row['region']} {row['query']}"):
                time.sleep(1.1)
                try:
                    results = search(client, query)
                except httpx.HTTPError as error:
                    print(f"  ! {row['name']}: {error}", file=sys.stderr)
                    results = []
                near = [
                    (distance_km(*approx, float(r["lat"]), float(r["lon"])), r) for r in results
                ]
                near = [item for item in near if item[0] <= MAX_SHIFT_KM]
                if near:
                    best = min(near, key=lambda item: item[0])
                    break
            if best is None:
                lat, lon, source = approx[0], approx[1], "APPROX"
                review.append(row["name"])
                print(f"APPROX {row['name']}")
            else:
                shift, result = best
                lat, lon, source = round(float(result["lat"]), 6), round(float(result["lon"]), 6), "OSM"
                print(f"OSM    {row['name']} ({shift:.1f} km 보정)")
            output.append(
                {
                    "id": row["id"],
                    "name": row["name"],
                    "themes": row["themes"],
                    "region": row["region"],
                    "lat": lat,
                    "lon": lon,
                    "description": row["description"],
                    "source": source,
                }
            )

    with OUTPUT.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(output)
    print(f"\n{len(output)}곳 저장, 확인 필요 {len(review)}곳: {', '.join(review) or '없음'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
