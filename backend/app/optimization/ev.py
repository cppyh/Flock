"""전기차 충전 계획. 합류 지점에서 기다리는 시간을 충전 시간으로 쓰는 정도를 계산한다.

배터리 잔량을 보낸 차량만 전기차로 본다. 주행 거리는 경로 시간에 평균 속도를 곱해 어림한다.
배터리 정보가 없는 차량만 있으면 이 모듈은 결과에 아무 영향도 주지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass

# 대기 중 덤으로 충전할 수 있는 양을 안내하는 최소 대기 시간
BONUS_MIN_WAIT_MINUTES = 5


@dataclass(frozen=True)
class EvConfig:
    # 기본값은 중형 전기 SUV 기준이다(배터리 77.4kWh, 전비 약 5.5km/kWh).
    battery_kwh: float = 77.4
    kwh_per_km: float = 0.18
    average_speed_kmh: float = 80
    charger_kw: float = 100
    charge_limit_percent: float = 80
    reserve_percent: float = 20
    separate_stop_minutes: float = 10


@dataclass(frozen=True)
class EvPlan:
    battery_percent: float
    battery_at_arrival: float
    battery_at_destination: float
    needs_charge: bool
    charger_available: bool
    charge_minutes: float
    covered_minutes: float
    extra_minutes: float
    bonus_percent: float

    def to_payload(self) -> dict:
        return {
            "battery_percent": round(self.battery_percent),
            "battery_at_arrival": round(self.battery_at_arrival),
            "battery_at_destination": round(self.battery_at_destination),
            "needs_charge": self.needs_charge,
            "charger_available": self.charger_available,
            "charge_minutes": round(self.charge_minutes, 1),
            "covered_by_wait_minutes": round(self.covered_minutes, 1),
            "extra_wait_minutes": round(self.extra_minutes, 1),
            "bonus_percent": round(self.bonus_percent),
        }


def percent_used(seconds: float, config: EvConfig) -> float:
    km = seconds / 3600 * config.average_speed_kmh
    return km * config.kwh_per_km / config.battery_kwh * 100


def charge_minutes(from_percent: float, to_percent: float, config: EvConfig) -> float:
    gap = max(0.0, to_percent - from_percent)
    return gap / 100 * config.battery_kwh / config.charger_kw * 60


def plan_ev(
    battery_percent: float,
    to_seconds: float,
    after_seconds: float,
    wait_minutes: float,
    has_charger: bool,
    config: EvConfig,
) -> EvPlan:
    """합류 지점까지 가고(to) 목적지까지 함께 가는(after) 동안의 배터리를 계산한다.

    목적지 도착 시 예비 잔량보다 적으면 충전이 필요하다. 합류 지점에 충전기가 있으면
    기다리는 시간과 겹치는 만큼은 그룹에 지연이 생기지 않고, 넘치는 만큼만 모두가 더 기다린다.
    충전기가 없거나 합류 지점까지도 못 가면 따로 들러 충전해야 하므로 들르는 시간까지 지연이 된다.
    """
    at_arrival = battery_percent - percent_used(to_seconds, config)
    at_destination = at_arrival - percent_used(after_seconds, config)
    needs = at_destination < config.reserve_percent
    if not needs:
        bonus = 0.0
        if has_charger and wait_minutes >= BONUS_MIN_WAIT_MINUTES and at_arrival < config.charge_limit_percent:
            gained = wait_minutes / 60 * config.charger_kw / config.battery_kwh * 100
            bonus = min(gained, config.charge_limit_percent - at_arrival)
        return EvPlan(battery_percent, at_arrival, at_destination, False, has_charger, 0.0, 0.0, 0.0, bonus)

    required = config.reserve_percent + percent_used(after_seconds, config)
    if has_charger and at_arrival >= 0:
        minutes = charge_minutes(at_arrival, min(required, config.charge_limit_percent), config)
        covered = min(minutes, max(0.0, wait_minutes))
        extra = minutes - covered
    else:
        minutes = charge_minutes(max(at_arrival, 0.0), required, config)
        covered = 0.0
        extra = minutes + config.separate_stop_minutes
    return EvPlan(battery_percent, at_arrival, at_destination, True, has_charger, minutes, covered, extra, 0.0)
