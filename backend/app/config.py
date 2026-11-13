"""환경 변수 기반 서버 설정. 값의 근거는 design.md §7.4, §8과 docs/algorithm.md에 있다."""

from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

from .optimization.ev import EvConfig
from .optimization.types import EngineConfig

REPO_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    database_url: str = "sqlite:///./meetroute.db"
    # 비어 있으면 실행할 때마다 임시 키를 만든다. 배포 환경에서는 반드시 지정한다.
    token_secret: str = ""
    token_ttl_minutes: int = 720

    max_vehicles: int = 3
    invite_ttl_minutes: int = 1440
    join_attempts_per_minute: int = 10

    stale_after_seconds: int = 20
    location_max_age_minutes: int = 5
    arrival_radius_m: float = 300
    # 내비의 도착 이벤트를 믿는 거리. 휴게소 입구와 공공데이터 좌표의 차이를 감안한다.
    nav_arrival_radius_m: float = 2000
    eta_refresh_seconds: int = 30
    reroute_delay_threshold_minutes: float = 10
    reroute_min_improvement_minutes: float = 5

    # estimate는 외부 전송이 없는 개발용 추정치, osrm은 실제 도로망 경로다.
    routing_provider: str = "estimate"
    osrm_base_url: str = "https://router.project-osrm.org"
    routing_timeout_seconds: float = 15
    rest_areas_csv: Path = REPO_ROOT / "data" / "rest_areas.csv"

    candidate_buffer_km: float = 2
    # estimate는 경로를 직선으로 그리므로 실제 도로 위 휴게소가 직선에서 멀다.
    estimate_candidate_buffer_km: float = 15
    candidate_top_k: int = 15
    candidate_min_k: int = 5
    recommendation_target_seconds: float = 5
    detour_max_minutes: float = 20
    normalize_min_range_minutes: float = 3

    weight_alpha: float = 1.0
    weight_beta: float = 0.7
    weight_gamma: float = 0.5
    weight_delta: float = 0.3
    weight_epsilon: float = 0.6
    weight_epsilon_depart_together: float = 1.2
    weight_zeta: float = 1.0

    # 전기차 충전 계획(배터리 잔량을 보낸 차량에만 적용)
    ev_battery_kwh: float = 77.4
    ev_kwh_per_km: float = 0.18
    ev_charger_kw: float = 100
    ev_reserve_percent: float = 20
    ev_charge_limit_percent: float = 80

    # 목적지 없는 드라이브
    drive_spots_csv: Path = REPO_ROOT / "data" / "drive_spots.csv"
    free_drive_tolerance_minutes: float = 15

    depart_together_window_minutes: float = 60
    rest_window_start_minutes: float = 90
    rest_window_end_minutes: float = 150
    meal_window_minutes: float = 30
    window_expansion_minutes: float = 30


def candidate_buffer_km(settings: Settings) -> float:
    if settings.routing_provider == "estimate":
        return max(settings.candidate_buffer_km, settings.estimate_candidate_buffer_km)
    return settings.candidate_buffer_km


def engine_config(settings: Settings) -> EngineConfig:
    return EngineConfig(
        candidate_buffer_m=candidate_buffer_km(settings) * 1000,
        top_k=settings.candidate_top_k,
        detour_max_minutes=settings.detour_max_minutes,
        normalize_min_range_minutes=settings.normalize_min_range_minutes,
        alpha=settings.weight_alpha,
        beta=settings.weight_beta,
        gamma=settings.weight_gamma,
        delta=settings.weight_delta,
        epsilon=settings.weight_epsilon,
        epsilon_depart_together=settings.weight_epsilon_depart_together,
        depart_together_window_minutes=settings.depart_together_window_minutes,
        rest_window_start_minutes=settings.rest_window_start_minutes,
        rest_window_end_minutes=settings.rest_window_end_minutes,
        meal_window_minutes=settings.meal_window_minutes,
        window_expansion_minutes=settings.window_expansion_minutes,
        reroute_min_improvement_minutes=settings.reroute_min_improvement_minutes,
        zeta=settings.weight_zeta,
        ev=EvConfig(
            battery_kwh=settings.ev_battery_kwh,
            kwh_per_km=settings.ev_kwh_per_km,
            charger_kw=settings.ev_charger_kw,
            reserve_percent=settings.ev_reserve_percent,
            charge_limit_percent=settings.ev_charge_limit_percent,
        ),
    )
