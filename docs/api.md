# API 명세

서버를 띄우면 `http://127.0.0.1:8000/docs`에서 OpenAPI 문서를 볼 수 있습니다. 이 문서는 그 외 규칙을 정리합니다.

## 인증

- 그룹을 만들거나 참여하면 `access_token`을 받습니다. 기본 유효 시간은 12시간입니다.
- 이후 모든 요청에 `Authorization: Bearer <token>` 헤더를 붙입니다. WebSocket도 같습니다. 토큰이 URL 로그에 남지 않도록 쿼리 문자열은 쓰지 않습니다.
- 초대 코드는 인증 수단이 아닙니다. 토큰을 받기 위한 초대 수단이며 24시간 뒤 만료됩니다.

## REST

design.md §7.1의 12개 경로에 아래 2개를 더했습니다.

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| `POST` | `/api/v1/groups` | 그룹 생성 | 누구나 |
| `POST` | `/api/v1/groups/join` | 초대 코드로 참여 | 누구나, IP당 분당 10회 |
| `GET` | `/api/v1/groups/{id}` | 그룹 조회, 재접속 후 재동기화 | 참여자 |
| `PATCH` | `/api/v1/groups/{id}/destination` | 공통 목적지 변경 | 그룹장 |
| `POST` | `/api/v1/groups/{id}/free-drive` | **추가.** 목적지 없는 드라이브: 시간·테마에 맞는 명소를 골라 목적지로 정함 | 그룹장 |
| `PATCH` | `/api/v1/groups/{id}/meeting-purpose` | 만남 목적 변경 | 그룹장 |
| `PATCH` | `/api/v1/groups/{id}/location-sharing` | **추가.** 위치 공유 동의·철회 | 본인 |
| `POST` | `/api/v1/groups/{id}/locations` | 위치 갱신(WebSocket의 백업 경로) | 동의한 참여자 |
| `POST` | `/api/v1/groups/{id}/recommendations` | 추천 계산 | 참여자 |
| `GET` | `/api/v1/groups/{id}/recommendations` | 최근 추천 조회 | 참여자 |
| `POST` | `/api/v1/groups/{id}/meeting-point` | 합류 장소 확정 | 그룹장 |
| `POST` | `/api/v1/groups/{id}/leave` | 탈퇴 | 본인 |
| `POST` | `/api/v1/groups/{id}/complete` | 세션 종료 | 그룹장 |
| `GET` | `/health` | 서버 상태 | 누구나 |

그룹장이 나가면 가장 먼저 참여한 차량이 그룹장이 됩니다. 마지막 차량이 나가면 세션이 닫힙니다.

### 합류 장소 확정의 충돌 처리

요청에 `expected_version`을 넣습니다. 그사이 그룹이 바뀌었으면 `409 VERSION_CONFLICT`를 돌려줍니다. 같은 판을 보고 들어온 동시 요청 중 하나만 성공합니다.

### 낡은 추천

아래 경우에 `409 RECOMMENDATION_OUTDATED`를 돌려줍니다. 앱은 추천을 다시 받습니다.

| 시점 | 조건 |
|---|---|
| 추천 계산이 끝날 때 | 계산하는 동안 목적지·만남 목적·식사 시각이 바뀌었거나 세션이 종료됨. 결과는 저장하지 않습니다 |
| 합류 장소를 고를 때 | 목적지 등이 바뀌어 무효가 됨 |
| 합류 장소를 고를 때 | 계획에 든 차량이 나갔거나 공유를 끊음 |
| 합류 장소를 고를 때 | 계획에 없던 차량이 위치를 공유하기 시작함 |

공유 중이지만 위치가 없어 계산에서 빠진 차량은 다시 계산해도 그대로이므로 선택을 막지 않습니다. `GET recommendations`의 `stale`도 같은 기준입니다.

### 목적지 없는 드라이브

```json
POST /api/v1/groups/{id}/free-drive
{ "hours": 2, "theme": "SEA", "private": true, "reroll": false }
```

| 필드 | 뜻 |
|---|---|
| `hours` | 왕복 주행 시간 1~5. 서버는 차량별 도로 경로 시간의 평균으로 왕복 시간을 계산해 ±15분(`FREE_DRIVE_TOLERANCE_MINUTES`) 안의 명소 중 하나를 무작위로 고릅니다 |
| `theme` | `SEA`(바다), `FOREST`(숲길), `NIGHT`(야경), `LAKE`(호수·강), `RANDOM`(완전 랜덤) |
| `private` | `true`면 그룹장 외에는 모두 합류할 때까지 목적지를 모릅니다 |
| `reroll` | 다른 곳 추천. 이번 드라이브에서 보여 준 곳을 빼고 고릅니다 |

- 위치를 공유 중인 차량이 1대 이상 있어야 합니다(`409 LOCATION_REQUIRED`).
- 범위 안에 명소가 없으면 가장 가까운 곳을 고르고 `free_drive.within_tolerance=false`와 `notice`로 알립니다.
- 응답은 그룹 화면이며 `free_drive`가 붙습니다: `plan_id`, `hours`, `theme`, `theme_label`, `private`, `revealed`, `round_trip_minutes`, `one_way_minutes`, `tolerance_minutes`, `within_tolerance`, `notice`, `region`, `description`.
- `PATCH destination`으로 목적지를 직접 정하면 드라이브 정보는 지워집니다.

### 비공개 목적지

목적지가 가려진 동안 그룹장이 아닌 차량은 **받는 모든 응답과 이벤트에서** 목적지를 볼 수 없습니다.

| 위치 | 그룹원이 받는 값 |
|---|---|
| 그룹 화면(`GET`, 참여 응답, `group.updated`, `meeting_point.selected`) | `destination: null`, `destination_hidden: true`, `free_drive.region`·`description`은 `null` |
| 추천 결과(`POST`/`GET recommendations`, `recommendations.ready`) | 목적지 후보의 `name`이 `"비공개 목적지"`, `latitude`·`longitude`가 `null`. 도착 예정 시각 등 계획 값은 그대로 |

- 서버는 이벤트를 받는 차량마다 그룹장용과 그룹원용 중 하나를 골라 보냅니다.
- **공개 시점:** 모든 차량이 합류 지점에 도착해 `ALL_ARRIVED`가 되는 순간입니다. 이때 `group.updated`에 `destination_revealed: true`가 붙습니다. 목적지를 합류 지점으로 고르면 그 자리에서 공개됩니다(모두 그곳으로 안내받아야 하므로).
- 그룹장이 나가 다른 차량이 그룹장이 되면 새 그룹장에게 목적지가 담긴 `group.updated`를 보냅니다.
- 그룹원 앱은 합류 지점만 내비 목적지로 등록합니다.

### 전기차 충전 계획

위치에 `battery_percent`(0~100)를 함께 보낸 차량을 전기차로 봅니다. 값이 없는 위치가 오면 마지막 값을 유지하고, 공유를 끄면 위치와 함께 지웁니다.

추천 후보마다 다음 필드가 붙습니다. 전기차가 없으면 `ev_plan`은 `null`이고 점수는 이전과 같습니다.

| 필드 | 뜻 |
|---|---|
| `ev_plan.{vehicle_id}.battery_at_arrival` | 합류 지점 도착 시 예상 잔량(%) |
| `ev_plan.{vehicle_id}.needs_charge` | 목적지에 예비 잔량(20%)보다 적게 도착하는지 |
| `ev_plan.{vehicle_id}.charge_minutes` | 필요한 충전 시간(급속 100kW 기준) |
| `ev_plan.{vehicle_id}.covered_by_wait_minutes` | 그중 다른 차량을 기다리는 시간과 겹치는 부분 |
| `ev_plan.{vehicle_id}.extra_wait_minutes` | 그룹 전체가 더 기다려야 하는 시간. 충전기가 없으면 따로 들르는 시간까지 포함 |
| `ev_plan.{vehicle_id}.bonus_percent` | 충전이 필요 없을 때 기다리는 동안 덤으로 채울 수 있는 양 |
| `ev_covered_minutes`, `ev_extra_minutes` | 후보 전체의 합(겹침)과 최댓값(추가 대기) |

## 오류 형식

```json
{ "error": { "code": "INVITE_EXPIRED", "message": "초대 코드가 만료되었습니다." } }
```

| 코드 | 상태 | 뜻 |
|---|---|---|
| `TOKEN_MISSING`, `TOKEN_INVALID`, `TOKEN_EXPIRED` | 401 | 인증 실패 |
| `NOT_A_MEMBER`, `LEADER_ONLY`, `CONSENT_REQUIRED` | 403 | 권한 없음 |
| `INVITE_NOT_FOUND`, `PLACE_NOT_FOUND`, `RECOMMENDATION_NOT_FOUND` | 404 | 없음 |
| `NO_DRIVE_SPOT` | 404 | 고를 수 있는 드라이브 명소가 없음 |
| `GROUP_FULL`, `VERSION_CONFLICT`, `RECOMMENDATION_OUTDATED`, `DESTINATION_REQUIRED`, `NOT_ENOUGH_VEHICLES`, `MEAL_TIME_REQUIRED`, `LOCATION_REQUIRED` | 409 | 상태 충돌 |
| `INVITE_EXPIRED`, `SESSION_CLOSED` | 410 | 만료 |
| `LOCATION_OUT_OF_SERVICE_AREA`, `LOCATION_TOO_OLD`, `LOCATION_FROM_FUTURE`, `MEAL_TIME_REQUIRED` | 422 | 잘못된 입력 |
| `TOO_MANY_ATTEMPTS` | 429 | 요청 제한 |
| `ROUTING_UNAVAILABLE` | 503 | 경로 조회 실패. `last_good`에 마지막 정상 결과가 들어 있습니다 |

## WebSocket

경로: `/ws/groups/{group_id}`

공통 형식: `{ "type", "group_id", "timestamp", "payload", "event_id" }`

| 방향 | 이벤트 | 내용 |
|---|---|---|
| 클라이언트 → 서버 | `location.updated` | `POST locations`와 같은 본문 |
| 클라이언트 → 서버 | `eta.requested` | ETA 재계산 요청 |
| 클라이언트 → 서버 | `presence.heartbeat` | 연결 유지 |
| 서버 → 클라이언트 | `participant.updated` | 차량 한 대의 상태 |
| 서버 → 클라이언트 | `group.updated` | **추가.** 목적지·목적·단계 변경 |
| 서버 → 클라이언트 | `recommendations.ready` | 추천 결과 |
| 서버 → 클라이언트 | `meeting_point.selected` | 합류 장소 확정 |
| 서버 → 클라이언트 | `session.closed` | `reason`: `COMPLETED`, `EMPTY` |
| 서버 → 클라이언트 | `error` | 해당 연결에만 전달 |

### 종료 코드

| 코드 | 뜻 | 클라이언트 동작 |
|---|---|---|
| 4401 | 인증 실패 | 재접속하지 않음 |
| 4403 | 그룹 탈퇴 | 재접속하지 않음 |
| 4409 | 새 연결로 교체됨 | 재접속하지 않음 |
| 4410 | 세션 종료 | 재접속하지 않음 |
| 그 외 | 네트워크 단절 등 | 지수 백오프로 재접속 |

앱은 첫 연결을 포함해 **연결될 때마다** 그룹과 최신 추천을 `GET`으로 다시 맞춥니다. 토큰이 만료되면 메시지를 보내지 않는 연결도 서버가 만료 시각에 `error`(`TOKEN_EXPIRED`)를 보낸 뒤 4401로 닫습니다.

## 위치 규칙

- 서비스 지역(위도 33~39, 경도 124~132) 밖의 좌표는 거부합니다.
- `timestamp`는 시간대를 포함해야 합니다. 5분보다 오래되었거나 1분 넘게 미래면 거부합니다.
- 저장된 값보다 오래된 위치는 조용히 무시합니다. 응답은 `{"accepted": false}`입니다.
- 공유 철회·탈퇴·세션 종료와 겹친 위치는 저장하지 않습니다. 응답은 같습니다.
- 마지막 위치가 20초 이상 지나면 `status`가 `STALE`이 됩니다.

### 내비 값

| 필드 | 뜻 |
|---|---|
| `nav_remain_seconds` | 차량 내비가 알려 준 합류 지점까지의 남은 시간 |
| `arrived_by_nav` | 내비가 합류 지점 도착을 알림 |
| `nav_target_id` | 위 값이 어느 합류 지점의 것인지. `place_id`를 보냅니다 |

- 합류 장소가 정해지기 전이거나 `nav_target_id`가 지금 합류 장소와 다르면 내비 값은 쓰지 않습니다.
- `arrived_by_nav`는 합류 지점에서 2km(`NAV_ARRIVAL_RADIUS_M`) 안일 때만 인정합니다. 좌표가 300m 안이면 내비와 상관없이 도착입니다.
- 도착한 차량의 ETA와 지연 표시는 이후 내비 값으로 바뀌지 않습니다.

## 참여자 상태

`ACTIVE`(공유 중), `STALE`(갱신 지연), `PAUSED`(공유 꺼짐), `NO_LOCATION`(위치 대기), `LEFT`(나감)

## 그룹 단계

`FORMING`(준비) → `EN_ROUTE`(합류 이동) → `ALL_ARRIVED`(전원 도착) → `CLOSED`(종료)

목적지나 만남 목적을 바꾸면 합류 계획이 무효가 되어 `FORMING`으로 돌아갑니다.
