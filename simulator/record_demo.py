"""오프라인 시연 모드용 녹화 데이터를 만든다.

실제 휴게소 공공데이터와 OSRM 도로망으로 추천을 계산해 앱 asset으로 저장한다.
실행: backend/.venv/Scripts/python simulator/record_demo.py
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from app.config import Settings, engine_config  # noqa: E402
from app.integrations.rest_areas import load_rest_areas  # noqa: E402
from app.integrations.routing.cache import CachedRouting  # noqa: E402
from app.integrations.routing.osrm import OsrmRouting  # noqa: E402
from app.optimization.engine import RecommendationEngine  # noqa: E402
from app.optimization.types import (  # noqa: E402
    Destination,
    LatLon,
    MeetingPurpose,
    RecommendationRequest,
    VehicleInput,
)
from app.services.recommendations import result_payload  # noqa: E402

SCENARIO = REPO_ROOT / "simulator" / "scenarios" / "three_cars.json"
TARGET = REPO_ROOT / "android-app" / "app" / "src" / "main" / "assets" / "demo" / "gangneung.json"


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    scenario = json.loads(SCENARIO.read_text(encoding="utf-8"))
    settings = Settings(_env_file=None, routing_provider="osrm")
    routing = CachedRouting(OsrmRouting(settings.osrm_base_url, 30))
    engine = RecommendationEngine(routing, load_rest_areas(settings.rest_areas_csv), engine_config(settings))

    now = datetime.now(timezone.utc).replace(microsecond=0)
    vehicles = [
        {
            "vehicle_id": f"demo-{vehicle['label'].lower()}",
            "nickname": vehicle["nickname"],
            "lat": vehicle["lat"],
            "lon": vehicle["lon"],
        }
        for vehicle in scenario["vehicles"]
    ]
    destination = scenario["destination"]
    request = RecommendationRequest(
        vehicles=tuple(
            VehicleInput(v["vehicle_id"], LatLon(v["lat"], v["lon"]), now) for v in vehicles
        ),
        destination=Destination(destination["name"], LatLon(destination["lat"], destination["lon"])),
        purpose=MeetingPurpose(scenario["meeting_purpose"]),
        now=now,
        departure_at=now,
    )
    result = engine.recommend(request)
    payload = result_payload(result, "demo-recommendation", "demo-group")

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(
        json.dumps(
            {
                "group_name": scenario["name"][:40],
                "destination": destination,
                "meeting_purpose": scenario["meeting_purpose"],
                "my_vehicle_id": vehicles[0]["vehicle_id"],
                "vehicles": vehicles,
                "recommendations": payload,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    best = payload["recommendations"][0]
    print(f"{TARGET} 저장: 1순위 {best['name']} ({best['reason']})")


if __name__ == "__main__":
    main()
