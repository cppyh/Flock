"""한국도로공사 휴게소 표준데이터를 추천 엔진이 읽는 data/rest_areas.csv로 정리한다.

사용법: python data/import_rest_areas.py [원본 CSV] [출력 CSV]
원본은 data/README.md의 공공데이터포털 데이터셋에서 내려받는다.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent
DEFAULT_SOURCE = DATA_DIR / "raw" / "한국도로공사_휴게소정보.csv"
DEFAULT_TARGET = DATA_DIR / "rest_areas.csv"

# 휴게소 이름의 괄호는 진행 방향의 종점 도시다. 예: 문막(강릉) = 강릉 방향 휴게소.
# 원본의 상행/하행 값은 노선에 따라 뒤섞여 있어(중앙선 등) 방향 판단에 쓰지 않는다.
# 좌표는 진행 방위각 계산에만 쓰는 도시 대표 지점이다.
TOWARD_POINTS = {
    "강릉": (37.7519, 128.8761), "공주": (36.4465, 127.1190), "광주": (35.1595, 126.8526),
    "남이": (36.5700, 127.4300), "논산": (36.1872, 127.0987), "당진": (36.8898, 126.6459),
    "대구": (35.8714, 128.6014), "대전": (36.3504, 127.3845), "목포": (34.8118, 126.3922),
    "무안": (34.9904, 126.4817), "부산": (35.1796, 129.0756), "삼척": (37.4499, 129.1651),
    "새만금": (35.7900, 126.5500), "서부산": (35.1500, 128.9700), "서울": (37.5665, 126.9780),
    "서천": (36.0803, 126.6914), "속초": (38.2070, 128.5918), "순천": (34.9506, 127.4872),
    "양양": (38.0754, 128.6190), "양평": (37.4917, 127.4876), "영덕": (36.4150, 129.3656),
    "영암": (34.8000, 126.6968), "완주": (35.9047, 127.1620), "울산": (35.5384, 129.3114),
    "익산": (35.9483, 126.9577), "인천": (37.4563, 126.7052), "일산": (37.6584, 126.7700),
    "장수": (35.6472, 127.5213), "전주": (35.8242, 127.1480), "제천": (37.1326, 128.1910),
    "창원": (35.2279, 128.6811), "천안": (36.8151, 127.1139), "청주": (36.6424, 127.4890),
    "춘천": (37.8813, 127.7298), "통영": (34.8544, 128.4331), "평택": (36.9921, 127.1129),
    "포항": (36.0190, 129.3435), "하남": (37.5393, 127.2148), "함양": (35.5205, 127.7252),
}

KIND_CODES = {"일반휴게소": "GENERAL", "간이휴게소": "SIMPLE", "화물차휴게소": "TRUCK"}

COLUMNS = [
    "id", "name", "route_name", "route_no", "direction", "bidirectional", "toward",
    "toward_lat", "toward_lon", "lat", "lon", "kind", "open_start", "open_end",
    "parking_spaces", "has_restaurant", "has_ev_charger", "has_gas_station", "has_toilet",
    "has_store", "phone", "data_date",
]


def normalize(row: dict[str, str]) -> dict[str, str]:
    name = row["휴게소명"].strip()
    match = re.search(r"\(([^)]*)\)\s*$", name)
    toward = match.group(1) if match else ""
    bidirectional = row["도로노선방향"].strip() == "양방향"
    if toward and toward not in TOWARD_POINTS:
        raise ValueError(f"방향 도시 좌표가 없습니다: {name}")
    if not toward and not bidirectional:
        raise ValueError(f"방향을 판단할 수 없는 휴게소입니다: {name}")
    point = TOWARD_POINTS.get(toward, ("", ""))
    return {
        "id": f"{row['도로노선번호'].strip()}-{name}",
        "name": name,
        "route_name": row["도로노선명"].strip(),
        "route_no": row["도로노선번호"].strip(),
        "direction": row["도로노선방향"].strip(),
        "bidirectional": "Y" if bidirectional else "N",
        "toward": toward,
        "toward_lat": str(point[0]),
        "toward_lon": str(point[1]),
        "lat": row["위도"].strip(),
        "lon": row["경도"].strip(),
        "kind": KIND_CODES[row["휴게소종류"].strip()],
        "open_start": row["휴게소운영시작시각"].strip(),
        "open_end": row["휴게소운영종료시각"].strip(),
        "parking_spaces": row["주차면수"].strip(),
        "has_restaurant": row["음식점유무"].strip(),
        "has_ev_charger": row["전기차충전소유무"].strip(),
        "has_gas_station": row["주유소유무"].strip(),
        "has_toilet": row["화장실유무"].strip(),
        "has_store": row["매점유무"].strip(),
        "phone": row["휴게소전화번호"].strip(),
        "data_date": row["데이터기준일자"].strip(),
    }


def main() -> None:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SOURCE
    target = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_TARGET
    with source.open(encoding="utf-8-sig", newline="") as file:
        rows = [normalize(row) for row in csv.DictReader(file)]
    rows.sort(key=lambda row: row["id"])
    with target.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"{len(rows)}개 휴게소를 {target}에 저장했습니다.")


if __name__ == "__main__":
    main()
