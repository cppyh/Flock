"""FastAPI 진입점."""

from __future__ import annotations

import logging
import random
import secrets

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from sqlalchemy import create_engine, event, inspect, text
from sqlalchemy.orm import sessionmaker

from .api import groups
from .api.deps import Container, ContainerDep
from .config import Settings, engine_config
from .errors import ApiError
from .integrations.drive_spots import load_drive_spots
from .integrations.rest_areas import load_rest_areas
from .integrations.routing import RoutingProvider, build_routing
from .models.orm import Base
from .optimization.engine import RecommendationEngine
from .services.groups import GroupService
from .services.rate_limit import SlidingWindowLimiter
from .services.recommendations import RecommendationService
from .services.tokens import TokenService
from .ws import routes as ws_routes
from .ws.manager import ConnectionManager

logger = logging.getLogger(__name__)


def _add_missing_columns(engine) -> None:
    """이전 버전이 만든 DB에 새로 생긴 열을 더한다. 새 열은 모두 비어 있어도 되는 열이다."""
    inspector = inspect(engine)
    with engine.begin() as connection:
        for table in Base.metadata.sorted_tables:
            if not inspector.has_table(table.name):
                continue
            existing = {column["name"] for column in inspector.get_columns(table.name)}
            for column in table.columns:
                if column.name in existing:
                    continue
                column_type = column.type.compile(dialect=engine.dialect)
                connection.execute(text(f'ALTER TABLE {table.name} ADD COLUMN "{column.name}" {column_type}'))
                logger.info("DB 열 추가: %s.%s", table.name, column.name)


def _session_factory(settings: Settings) -> sessionmaker:
    is_sqlite = settings.database_url.startswith("sqlite")
    connect_args = {"check_same_thread": False} if is_sqlite else {}
    engine = create_engine(settings.database_url, connect_args=connect_args)
    if is_sqlite:
        @event.listens_for(engine, "connect")
        def enable_wal(connection, _record):
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA foreign_keys=ON")
    Base.metadata.create_all(engine)
    _add_missing_columns(engine)
    return sessionmaker(engine, expire_on_commit=False)


def create_app(
    settings: Settings | None = None,
    routing: RoutingProvider | None = None,
    rng: random.Random | None = None,
) -> FastAPI:
    settings = settings or Settings()
    secret = settings.token_secret
    if not secret:
        logger.warning("TOKEN_SECRET이 없어 임시 키를 사용합니다. 서버를 재시작하면 기존 토큰이 무효가 됩니다.")
        secret = secrets.token_urlsafe(48)

    rest_areas = load_rest_areas(settings.rest_areas_csv)
    drive_spots = load_drive_spots(settings.drive_spots_csv)
    routing = routing or build_routing(settings)
    sessions = _session_factory(settings)
    connections = ConnectionManager()
    tokens = TokenService(secret, settings.token_ttl_minutes)
    engine = RecommendationEngine(routing, rest_areas, engine_config(settings))

    app = FastAPI(title="MeetRoute API", version="0.1.0")
    app.state.container = Container(
        settings=settings,
        tokens=tokens,
        groups=GroupService(
            settings, sessions, tokens, routing, connections.is_online, drive_spots=drive_spots, rng=rng
        ),
        recommendations=RecommendationService(settings, sessions, engine),
        connections=connections,
        join_limiter=SlidingWindowLimiter(settings.join_attempts_per_minute),
        rest_area_count=len(rest_areas),
        drive_spot_count=len(drive_spots),
    )

    @app.exception_handler(ApiError)
    async def handle_api_error(_: Request, error: ApiError) -> JSONResponse:
        return JSONResponse(status_code=error.status, content=error.to_body())

    @app.get("/health", tags=["system"])
    def health(container: ContainerDep) -> dict:
        return {
            "status": "ok",
            "routing_provider": container.settings.routing_provider,
            "rest_areas": container.rest_area_count,
            "drive_spots": container.drive_spot_count,
        }

    app.include_router(groups.router)
    app.include_router(ws_routes.router)
    return app


app = create_app()
