# 아키텍처

전체 구조는 design.md §5에 있습니다. 이 문서는 구현 구조와 운영 규칙을 정리합니다.

## 저장소

| 폴더 | 내용 |
|---|---|
| `android-app/app` | 차량 앱 |
| `android-app/navi-smoke` | Pleos SDK 동작 확인용 최소 시험 APK |
| `backend` | FastAPI 서버, 추천 엔진 |
| `simulator` | 가상 차량, 실험, 시연 데이터 녹화 |
| `data` | 휴게소 공공데이터 |
| `tests/e2e` | 실제 서버와 시뮬레이터로 전체 흐름 시험 |

## 서버

```text
api/ ws/          → 요청 받기, 이벤트 보내기
services/         → 그룹 세션, 추천 요청, 토큰
optimization/     → 추천 엔진 (프레임워크 의존 없음)
integrations/     → 경로 제공자, 휴게소 데이터
models/           → DB, 요청 스키마
```

서비스는 상태를 바꾼 뒤 전파할 이벤트를 **돌려주고**, API 계층이 전송합니다. 덕분에 서비스는 WebSocket을 몰라도 됩니다.

## 앱

```text
ui/          화면, ViewModel
domain/      모델, 상태 반영, 전송·주행 정책, 음성 문구
data/        REST, WebSocket, 저장소 구현
location/    위치 공급자, 공유 제어, 포그라운드 서비스
navigation/  내비게이션 연동
voice/       음성 출력
di/          구현 선택
```

| 인터페이스 | 구현 | 쓰는 경우 |
|---|---|---|
| `VehicleLocationSource` | `PleosFusedLocationSource` | 1순위 |
| | `PleosNaviLocationSource` | FusedLocation 미지원 시 |
| | `AndroidLocationSource` | 일반 Android 개발 환경 |
| | `SimulatedLocationSource` | 시나리오 시험 |
| `NavigationGateway` | `PleosNaviHelperGateway` | Pleos 차량 |
| | `RecordingNavigationGateway` | 내비 없는 환경, 시험 |
| `MeetPointRepository` | `RoadRoutingMeetPointRepository` | 실제 서버 |
| | `FixtureMeetPointRepository` | 오프라인 시연 |

위치 공급자가 실패하거나 30초 동안 값을 주지 않으면 `LocationSharingController`가 다음 순위로 넘어갑니다. 모두 실패해도 멈추지 않고 60초 뒤 처음부터 다시 시도합니다. 화면에는 현재 위치 출처를 항상 표시합니다.

위치가 바뀔 때만 콜백을 주는 공급자는 시작할 때와 콜백이 뜸할 때 5초마다 마지막 위치를 조회합니다. 이 값으로 정차 차량도 STALE이 되지 않고 주행 판정도 풀립니다.

`NaviHelperPort`는 SDK 호출을 감싸는 얇은 경계입니다. 실패·시간 초과 처리를 SDK 없이 시험하려고 둡니다.

## 동시 요청

| 경합 | 처리 |
|---|---|
| 위치 전송 ↔ 공유 철회·탈퇴·종료 | 외부 경로 조회는 트랜잭션 밖에서 하고, 저장은 `공유 중일 때만` 조건을 건 UPDATE 한 문장으로 합니다 |
| 공유 철회의 좌표 삭제 | ORM 변경 감지에 맡기지 않고 UPDATE 한 문장으로 지웁니다. 첫 위치와 겹쳐도 남지 않습니다 |
| 추천 계산 ↔ 계획 변경 | 계산을 시작할 때의 `version`이 그대로일 때만 저장합니다. 행을 잠그며 확인합니다 |
| `version` 증가 | 읽고 쓰지 않고 `version + 1` 한 문장으로 올립니다 |
| WebSocket 전파 ↔ 끊긴 소켓 | 한 소켓의 실패는 건너뛰고 나머지에 계속 보냅니다 |

## 경로 제공자 추가

`backend/app/integrations/routing/base.py`의 `RoutingProvider`를 구현합니다. 메서드는 `route()`와 `matrix()` 두 개입니다. `build_routing()`에 등록하면 끝납니다.

## 개인정보

| 데이터 | 저장 | 삭제 시점 |
|---|---|---|
| 차량 위치 | 차량별 최신 1건만 | 공유 철회, 탈퇴, 세션 종료 즉시 |
| 추천 결과 | 도착 예상 시각, 우회 시간. 좌표 이력은 없음 | 자동 삭제 미구현(아래 참고) |
| 닉네임 | 세션 기간 | 자동 삭제 미구현 |
| 토큰 | 서버 저장 없음, 앱 내부 저장소 | 세션 종료 시 앱에서 삭제 |

- 그룹 참여만으로 공유가 시작되지 않습니다. 기본값이 꺼짐입니다.
- 위치 좌표, 토큰, 초대 코드는 서버 로그에 남기지 않습니다.
- **미구현:** 종료된 세션의 행을 주기적으로 지우는 작업. 실제 사용자 테스트 전에 필요합니다.
- **주의:** OSRM 공개 데모 서버를 쓰면 좌표가 외부로 나갑니다. 시뮬레이션 차량으로만 쓰고, 실제 사용자 위치에는 쓰지 않습니다.

## 배포 전에 할 일

- `TOKEN_SECRET` 지정, HTTPS/WSS 적용
- `backend`를 PostgreSQL로 전환. 정원 초과 방지가 프로세스 잠금이므로 서버 여러 대로 늘리기 전에 행 잠금으로 교체합니다.
- 요청 제한과 연결 관리가 프로세스 메모리 기반입니다. 서버를 2대 이상 쓰면 공유 저장소가 필요합니다.
- 국내 경로 API로 교체
