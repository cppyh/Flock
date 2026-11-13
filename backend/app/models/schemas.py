"""REST 요청 스키마. 응답은 services의 view 함수가 만든다."""

from __future__ import annotations

from datetime import datetime
from typing import Annotated

from pydantic import AfterValidator, BaseModel, Field

from ..integrations.drive_spots import DriveTheme
from ..optimization.types import MeetingPurpose


def _require_timezone(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("시간대가 포함된 시각이어야 합니다.")
    return value


AwareDatetime = Annotated[datetime, AfterValidator(_require_timezone)]
Latitude = Annotated[float, Field(ge=-90, le=90)]
Longitude = Annotated[float, Field(ge=-180, le=180)]
Nickname = Annotated[str, Field(min_length=1, max_length=20)]


class CreateGroupRequest(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    nickname: Nickname
    is_simulated: bool = False


class JoinGroupRequest(BaseModel):
    invite_code: str = Field(min_length=6, max_length=6)
    nickname: Nickname
    is_simulated: bool = False


class DestinationRequest(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    lat: Latitude
    lon: Longitude


class MeetingPurposeRequest(BaseModel):
    meeting_purpose: MeetingPurpose
    meal_time: AwareDatetime | None = None


class LocationSharingRequest(BaseModel):
    enabled: bool


class LocationUpdate(BaseModel):
    lat: Latitude
    lon: Longitude
    timestamp: AwareDatetime
    heading: float | None = None
    accuracy_m: float | None = Field(default=None, ge=0)
    source: str | None = Field(default=None, max_length=30)
    # 차량 내비게이션이 알려 준 합류 지점까지의 남은 시간과 도착 이벤트.
    # nav_target_id는 이 값이 어느 합류 지점의 것인지 밝혀, 이전 계획의 값이 섞이지 않게 한다.
    nav_remain_seconds: float | None = Field(default=None, ge=0)
    arrived_by_nav: bool = False
    nav_target_id: str | None = Field(default=None, max_length=80)
    # 전기차가 차량 SDK에서 읽은 배터리 잔량(%). 내연기관차나 읽지 못한 경우에는 보내지 않는다.
    battery_percent: float | None = Field(default=None, ge=0, le=100)


class FreeDriveRequest(BaseModel):
    """목적지 없는 드라이브. hours는 왕복 주행 시간이고 결과는 ±허용 오차 안에서 고른다."""

    hours: int = Field(ge=1, le=5)
    theme: DriveTheme = DriveTheme.RANDOM
    private: bool = False
    # 다른 곳 추천: 이번 드라이브에서 이미 보여 준 곳을 빼고 고른다.
    reroll: bool = False


class SelectMeetingPointRequest(BaseModel):
    recommendation_id: str
    place_id: str
    expected_version: int
