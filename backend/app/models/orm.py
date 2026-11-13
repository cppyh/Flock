"""DB 스키마. SQLite로 시작하고 SQLAlchemy를 통해 PostgreSQL로 옮길 수 있게 한다.

위치는 차량별 최신값 한 건만 보관하고 탈퇴·공유 해제·세션 종료 시 지운다(design.md §15).
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy import JSON, Boolean, DateTime, Float, ForeignKey, Integer, String, TypeDecorator
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class UtcDateTime(TypeDecorator):
    """SQLite는 시간대를 버리므로 UTC로 저장하고 읽을 때 시간대를 복원한다."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect):
        return None if value is None else value.astimezone(timezone.utc).replace(tzinfo=None)

    def process_result_value(self, value: datetime | None, dialect):
        return None if value is None else value.replace(tzinfo=timezone.utc)


class Base(DeclarativeBase):
    pass


class Group(Base):
    __tablename__ = "groups"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    name: Mapped[str] = mapped_column(String(40))
    invite_code: Mapped[str] = mapped_column(String(6), index=True)
    invite_expires_at: Mapped[datetime] = mapped_column(UtcDateTime)
    max_vehicles: Mapped[int] = mapped_column(Integer)

    destination_name: Mapped[str | None] = mapped_column(String(80))
    destination_lat: Mapped[float | None] = mapped_column(Float)
    destination_lon: Mapped[float | None] = mapped_column(Float)

    meeting_purpose: Mapped[str] = mapped_column(String(20), default="ANY")
    meal_time: Mapped[datetime | None] = mapped_column(UtcDateTime)

    # 목적지 없는 드라이브. 비공개면 그룹장 외에는 모두 합류할 때까지 목적지를 모른다.
    drive_hours: Mapped[int | None] = mapped_column(Integer)
    drive_theme: Mapped[str | None] = mapped_column(String(20))
    drive_plan: Mapped[dict | None] = mapped_column(JSON)
    drive_history: Mapped[list | None] = mapped_column(JSON)
    destination_private: Mapped[bool | None] = mapped_column(Boolean, default=False)
    destination_revealed_at: Mapped[datetime | None] = mapped_column(UtcDateTime)

    phase: Mapped[str] = mapped_column(String(20), default="FORMING")
    departed_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    selected_meeting_point_id: Mapped[str | None] = mapped_column(String(80))
    meeting_point: Mapped[dict | None] = mapped_column(JSON)
    version: Mapped[int] = mapped_column(Integer, default=1)

    created_at: Mapped[datetime] = mapped_column(UtcDateTime)
    closed_at: Mapped[datetime | None] = mapped_column(UtcDateTime)

    participants: Mapped[list[Participant]] = relationship(
        back_populates="group", order_by="Participant.joined_at", cascade="all, delete-orphan"
    )


class Participant(Base):
    __tablename__ = "participants"

    vehicle_id: Mapped[str] = mapped_column(String(32), primary_key=True)
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id"), index=True)
    nickname: Mapped[str] = mapped_column(String(20))
    role: Mapped[str] = mapped_column(String(10))
    is_simulated: Mapped[bool] = mapped_column(Boolean, default=False)
    left_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    joined_at: Mapped[datetime] = mapped_column(UtcDateTime)

    location_sharing: Mapped[bool] = mapped_column(Boolean, default=False)
    lat: Mapped[float | None] = mapped_column(Float)
    lon: Mapped[float | None] = mapped_column(Float)
    heading: Mapped[float | None] = mapped_column(Float)
    accuracy_m: Mapped[float | None] = mapped_column(Float)
    location_source: Mapped[str | None] = mapped_column(String(30))
    location_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    # 전기차 배터리 잔량(%). 위치와 같은 차량 정보로 보고 공유를 끝낼 때 함께 지운다.
    battery_percent: Mapped[float | None] = mapped_column(Float)

    eta_seconds: Mapped[float | None] = mapped_column(Float)
    eta_source: Mapped[str | None] = mapped_column(String(20))
    eta_updated_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    delayed: Mapped[bool] = mapped_column(Boolean, default=False)
    arrived_at: Mapped[datetime | None] = mapped_column(UtcDateTime)

    group: Mapped[Group] = relationship(back_populates="participants")

    @property
    def is_active(self) -> bool:
        return self.left_at is None

    def has_usable_location(self, now: datetime, max_age: timedelta) -> bool:
        """추천 계산에 넣을 수 있는 위치가 있는지 알려 준다."""
        return (
            self.is_active
            and self.location_sharing
            and self.location_at is not None
            and now - self.location_at <= max_age
        )


# 공유를 끝낼 때 지우는 값. 진행 중인 위치 저장과 겹쳐도 남지 않도록 UPDATE 한 번으로 지운다.
ERASED_LOCATION = {
    "location_sharing": False,
    "lat": None,
    "lon": None,
    "heading": None,
    "accuracy_m": None,
    "location_source": None,
    "location_at": None,
    "battery_percent": None,
    "eta_seconds": None,
    "eta_source": None,
    "eta_updated_at": None,
}


class RecommendationRun(Base):
    __tablename__ = "recommendation_runs"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id"), index=True)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime)
    is_current: Mapped[bool] = mapped_column(Boolean, default=True)
    payload: Mapped[dict] = mapped_column(JSON)
