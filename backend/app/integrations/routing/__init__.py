"""도로 경로 제공자. 제공자를 추가할 때는 base.RoutingProvider만 구현하면 된다."""

from __future__ import annotations

from ...config import Settings
from .base import RoutingProvider
from .cache import CachedRouting
from .estimate import EstimatedRouting
from .osrm import OsrmRouting


def build_routing(settings: Settings) -> RoutingProvider:
    if settings.routing_provider == "estimate":
        return EstimatedRouting()
    if settings.routing_provider == "osrm":
        return CachedRouting(OsrmRouting(settings.osrm_base_url, settings.routing_timeout_seconds))
    raise ValueError(f"지원하지 않는 경로 제공자입니다: {settings.routing_provider}")
