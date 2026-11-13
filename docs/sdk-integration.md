# Pleos SDK 연동 기록

2026-11-04에 Pleos Maven 저장소의 실제 AAR을 내려받아 `javap`로 확인한 내용입니다. 문서와 AAR이 다르면 **AAR을 기준**으로 합니다.

## 1. 저장소와 의존성

```kotlin
// settings.gradle.kts
maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }

// libs.versions.toml
naviHelper = "3.0.5"
fusedLocation = "1.1.3"
vehicle = "2.3.2"   // 전기차 배터리 잔량(EvBattery)
```

- 저장소는 인증 없이 접근됩니다.
- **FusedLocation은 실제로 배포되어 있습니다.** 배포 버전은 `1.0.4`, `1.1.3`입니다. 문서에 나오는 `1.1.2`는 저장소에 없습니다.
- 그 밖에 `Vehicle 2.3.2`, `TextToSpeech 3.3.1`, `SpeechToText 3.3.1`도 받을 수 있습니다.
- `FusedLocation` AAR은 `android.hardware.type.automotive`를 `required=true`로 선언합니다. 합쳐진 앱 매니페스트에도 포함됩니다.

## 2. 계획서의 미확인 항목에 대한 답

| 질문 (design.md §9) | 답 | 근거 |
|---|---|---|
| FusedLocation을 쓸 수 있는가 | **됩니다.** 문서의 Not Available Yet과 달리 에뮬레이터에서 `isSupported=true`이고 1초마다 값이 옵니다 | 2026-11-07 실측 |
| `getRouteStateInfo`가 남은 시간·거리를 주는가 | **주지 않습니다.** `naviMode`, `routeOption`, `trafficStatus` 등만 있습니다 | `RouteStateInfo` 클래스 |
| 그러면 남은 시간은 어디서 받는가 | **`getWaypointInfo()`** → `WaypointInfo.remainTime/remainDistance`. 최종 목적지는 `getDestinationInfo()` | `WaypointInfo`, `DestinationInfo` 클래스 |
| `getCurrentLocationInfo`로 차량 위치를 받는가 | 위도·경도·방위각을 줍니다. **측정 시각은 없어** 수신 시각을 씁니다 | `CurrentLocationInfo` 클래스 |
| `addWaypoint`는 안내 중에만 되는가 | **안내 중에 됩니다.** 안내가 없을 때 단독 호출은 내비 화면만 열리고 경유지가 들어가지 않습니다 | §5.2 |

`WaypointInfo.remainTime` 덕분에 합류 지점까지의 실제 ETA를 외부 경로 API 없이 받을 수 있습니다. 앱은 이 값을 `nav_remain_seconds`로 서버에 보내고, 서버는 `eta_source=VEHICLE_NAV`로 표시합니다. 단위는 **초**이고 `remainDistance`는 **미터**입니다(§5.2). 음수는 값이 없는 것으로 봅니다.

## 3. 문서 샘플과 실제 코드의 차이

| 항목 | 문서 | 실제 AAR |
|---|---|---|
| 경유지 콜백 | Kotlin 예시 `onWaypointInfo` | **`onWayPointInfo`** (대소문자 주의) |
| `RequestWaypointInfo` | 필드 7개 | `poiProvider` 포함 8개(기본값 있음) |
| `FusedLocationListener.onChanged` | `LocationData?` | 동일. null을 처리해야 합니다 |

## 4. 권한

| 권한 | 용도 | 등급 |
|---|---|---|
| `pleos.car.permission.NAVI_ROUTE` | `get*Info()`, `requestTrip()` | 문서 미기재 |
| `pleos.car.permission.NAVI_ROUTE_SEARCH` | `requestRoute()`, `requestRouteWithWaypoints()`, `addWaypoint()`, `removeWaypoint()` 등 | 문서 미기재 |
| `pleos.car.permission.NAVI_CUSTOM_MAP` | `requestNavigationScreen()` | 문서 미기재 |
| `pleos.car.permission.FUSED_LOCATION` | FusedLocation 전체 | **dangerous** (런타임 요청) |
| `pleos.car.permission.CAR_ENERGY` | Vehicle SDK `EvBattery` 읽기 | 위치 공유를 켤 때 함께 요청. 에뮬레이터에서는 설치만으로 허용됨(2026-11-08). 거부해도 공유는 동작 |

앱은 시작할 때 내비 권한을, 위치 공유를 켤 때 위치 권한을 요청합니다. **내비 연결은 권한 창이 닫힌 뒤에 시작합니다.** Android 13부터 알림 권한이 있어야 공유 중 알림이 보여 함께 요청합니다.

Android 위치 권한은 위치 타입 포그라운드 서비스의 조건입니다. Pleos 권한만 있으면 서비스 없이 앱이 화면에 있을 때만 공유합니다. 서비스는 `startService()`로 시작합니다. `startForegroundService()`는 포그라운드 전환에 실패하면 시간 초과로 앱을 종료시키기 때문입니다.

## 5. 경유지 등록 전략

SDK는 요청의 결과를 돌려주지 않고, 문서도 "이벤트는 내비 앱의 구현과 상태에 따라 달라질 수 있다"고 적습니다. 그래서 `PleosNaviHelperGateway`는 콜백을 다시 확인하라는 신호로만 쓰고 **성공은 내비의 실제 상태를 조회해 판단합니다.**

**적용 순서**

1. `getRouteStateInfo()`의 `naviMode`로 안내 중인지 확인합니다. `ROUTE_GUIDING`과 `SIMULATION_GUIDING`이 안내 중입니다. **`isRouting`은 안내 중에도 false라 쓰지 않습니다.** 응답이 없으면 안내 중이 아닌 것으로 보지 않고 실패로 알립니다.
2. 안내 중이 아니면 `requestRouteWithWaypoints(목적지, [합류 지점])`를 호출합니다.
3. 안내 중이면 `getWaypointInfo()`와 `getDestinationInfo()`를 조회합니다. 둘 중 하나라도 응답이 없으면 실패로 알립니다.
4. 목적지가 그룹 목적지와 다르면 아무것도 바꾸지 않고 운전자에게 묻습니다. 확인하면 `requestRouteWithWaypoints`로 목적지와 합류 지점을 요청합니다. 경유지 조회에 좌표가 없어 운전자 경유지는 다시 넣지 못하므로, 지워질 수 있다고 화면에서 미리 알립니다.
5. 목적지가 같으면 이전 합류 지점을 지우고 빈 슬롯에 새 합류 지점을 넣습니다. 삭제는 `onWaypointChanged`나 `onRouteEnded`가 온 뒤 조회로 확인하고, 그다음에만 추가합니다. 경유지는 최대 2개(`FIRST`, `SECOND`)이고, 슬롯이 가득 차면 실패로 알립니다.

**같은 장소 판단:** 목적지는 300m 이내이거나, 이름이 같고 5km 이내면 같은 장소로 봅니다. 내비에서 고른 강릉역과 앱의 강릉역은 기준 좌표가 2.5km쯤 다를 수 있습니다.

**경유지 식별:** 슬롯 번호는 밀리고 조회에 좌표가 없어 이름으로 찾습니다. 넘겨준 이름은 그대로 돌아옵니다. 앱을 다시 켜도 찾을 수 있도록 넣은 합류 지점을 저장합니다.

**성공 판단**

| 결과 | 조건 |
|---|---|
| 성공 | 목적지와 경유지 조회 결과가 원하는 계획과 같음 |
| 실패 | `onError`, 또는 요청 호출 자체의 예외 |
| 확인 불가 | 탐색 완료 신호는 왔지만 60초 안에 조회로 확인하지 못함 |
| 시간 초과 | 60초 동안 확인도 완료 신호도 없음 |

`onRouteStarted` 뒤에는 탐색이 끝날 때까지 성공으로 보지 않습니다. 탐색 완료 신호는 `onRouteEnded`, `onWaypointChanged`, `onRoutingCompleted`, `onReRouteEnded`입니다. 어느 것도 오지 않는 구현에 대비해 8초 뒤에는 조회 결과로 판단합니다.

**진행 상황**

- ETA는 합류 경유지의 값만 씁니다. 목적지에서 합류하면 `getDestinationInfo()`의 값을 씁니다.
- 도착 이벤트는 이름이나 슬롯이 합류 대상과 같을 때만 인정합니다. 운전자의 다른 경유지나 최종 목적지 도착은 세지 않습니다.
- 합류 계획이 바뀌면 진행 상황을 지웁니다. 서버에는 `nav_target_id`로 어느 지점의 값인지 함께 보냅니다.
- `onRouteCancelled`와 `FREE_DRIVE`만으로는 안내가 끝났다고 보지 않습니다. 운전자가 경유지를 바꿔도 같은 신호가 오기 때문입니다. 60초가 지나도 안내가 돌아오지 않을 때만 추적을 멈춥니다. 이미 도착했다면 도착 상태는 남깁니다.

등록이 끝나면 앱에서 내비 화면 열기(`requestNavigationScreen(Foreground)`)와 경로 재탐색(`requestReRoute`, 안내 중에만 동작)을 쓸 수 있습니다. 재탐색은 경로를 벗어나지 않았으면 내비가 아무 콜백도 보내지 않아(실측) 요청만 보내고 "요청했습니다"로 알립니다. Pleos 내비게이션이 없는 환경에서는 "요청만 기록했습니다"로 표시하고 성공으로 표시하지 않습니다. 연결에 실패해도 30초 간격으로 다시 시도하고, 화면의 "내비 다시 연결"로 바로 시도할 수 있습니다.

logcat 태그 `MeetRouteNavi`에 요청과 콜백이 순서대로 남습니다. 차량 좌표는 기록하지 않습니다.

## 5.1 위치 수집

| 상황 | 처리 |
|---|---|
| 세워 둔 차량 | 콜백은 위치가 바뀔 때만 오므로 시작할 때와 5초마다 `getLastKnownLocation()`을 조회합니다 |
| 측정 시각이 현재와 크게 다름 | 시각 기준이 달라 서버가 거부하지 않도록 수신 시각을 씁니다 |
| 서비스 지역 밖 좌표, (0,0) | 값이 없는 것으로 다룹니다. 서버의 서비스 지역(위도 33~39, 경도 124~132)과 같습니다 |
| 30초 동안 쓸 수 있는 값이 없음 | 다음 공급자로 넘어갑니다 |
| 모든 공급자 실패 | 화면에 알리고 60초 뒤 다시 시도합니다 |

**POI ID:** 휴게소의 Pleos POI ID를 알 수 없어 빈 문자열을 넘깁니다. 에뮬레이터에서 빈 값으로 등록됩니다.

## 5.1.1 전기차 배터리 (Vehicle SDK)

`PleosEvBatterySource`가 `Vehicle(context).getEvBattery()`로 충전 상태(SoC, 정수 %)를 읽고, 위치를 보낼 때 `battery_percent`로 함께 보냅니다. 서버는 이 값으로 합류 지점에서 기다리는 시간을 충전 시간으로 쓰는 계획을 세웁니다(docs/algorithm.md).

| 사용한 API | 용도 |
|---|---|
| `registerEvBatteryStateOfCharge(EvBatteryStateOfChargeListener)` | 변경 알림 |
| `getEvBatteryStateOfCharge(onSuccess, onFailed)` | 시작할 때와 30초마다 직접 조회. 알림이 오지 않는 환경 대비 |
| `VehicleListener.onFailed(Exception)` | 기록만 하고 공유는 계속 |

- 권한이 없거나 서비스가 없으면 값 없이 위치만 보냅니다. 0~100 밖의 값은 읽지 못한 것으로 봅니다.
- 공유를 끄면 리스너를 해제하고 마지막 값도 버립니다. 서버도 위치와 함께 지웁니다.
- **실측(2026-11-08, 개발 PC Pleos 에뮬레이터):** `initialize()` 후 약 3초 뒤 서비스가 연결되고 `EV_BATTERY_SOC = 100`이 왔습니다. 조회와 알림 모두 동작했고 서버에 100%로 저장됐습니다. 연결 전 첫 위치에는 배터리 값이 비어 갈 수 있으며, 다음 전송(최대 15초 뒤)부터 붙습니다.
- 같은 SDK에 `getEvBatteryCapacity`, `RangeRemainingListener`(남은 주행 거리)도 있습니다. 지금은 서버의 기본 배터리 용량을 쓰고, 차량별 용량 반영은 다음 단계입니다.

## 5.2 Pleos 에뮬레이터 실측 (2026-11-07)

Pleos Connect 에뮬레이터 v2.1.0에서 `navi-smoke`와 앱으로 확인했습니다. MX450 PC에서 소프트웨어 렌더링으로 돌린 값이라 시간은 GPU PC에서 달라질 수 있습니다.

**경로 상태**

- `isRouting`은 안내 중에도 false입니다. 안내 여부는 `naviMode`로 봅니다. 안내 없음은 `SAFE_GUIDING`, 안내 중은 `ROUTE_GUIDING`입니다.
- `termsAgreement`는 새 AVD에서 처음부터 true였습니다. 내비 앱에서 따로 동의할 것이 없습니다.

**콜백 순서**

| 동작 | 순서 | 걸린 시간 |
|---|---|---|
| 안내 없을 때 경로 요청 | `onRouteStarted`, `onRouteEnded`, `ROUTE_DRIVE` | 19~29초 |
| 안내 중 경로 교체 | `onRouteCancelled`, `FREE_DRIVE`, `onRouteStarted`, `onRouteEnded`, `ROUTE_DRIVE` | 약 21초 |
| 경유지 추가·삭제 | `onRouteCancelled`, `FREE_DRIVE`, `onWaypointChanged`, `onRouteEnded`, `ROUTE_DRIVE` | 15~34초 |
| 안내 취소 | `onRouteCancelled`, `FREE_DRIVE` | 약 2초 |

- `onRoutingCompleted`와 `onReRouteEnded`는 한 번도 오지 않았습니다.
- `onRouteEnded`는 안내가 끝날 때가 아니라 **탐색이 끝날 때마다** 옵니다.
- 경로를 다시 만드는 동안 `naviMode`는 `SAFE_GUIDING`이고 목적지·경유지 조회는 빈 값입니다. `naviMode`는 `onWaypointChanged`보다 5초쯤 먼저 돌아옵니다.

**조회 값**

- `remainTime`은 초, `remainDistance`는 미터입니다. 거리는 반올림돼 나옵니다.
- `WaypointInfo`의 위도·경도는 0.0입니다. 이름은 넘겨준 값 그대로 오고 주소는 내비가 채웁니다. `DestinationInfo`에는 좌표가 있습니다.
- 경유지를 지우면 뒤 번호가 앞으로 당겨집니다.

**위치**

- FusedLocation은 `adb emu geo fix`를 따릅니다. `isSupported=true`이고 1초마다 옵니다. 앱은 이 값을 씁니다.
- Navi 현재 위치는 성남시 수정구 시흥동(37.4127212, 127.094681)에 고정이고 `geo fix`를 따르지 않습니다. 로그에 `NMEA checksum mismatch`가 남습니다. 그래서 **내비 경로는 항상 성남에서 출발**하고, 내비의 도착 콜백은 받을 수 없습니다.
- 앱의 도착 판정은 서버가 위치로 합니다. `geo fix`로 합류 지점에 옮기면 도착 처리됩니다.

**장소 검색**

- SDK에는 검색 결과를 돌려주는 기능이 없습니다. `requestNavigationScreen(SearchPlace(키워드))`로 내비의 검색 화면을 엽니다.
- 장소 이름, 상호, 도로명 주소를 모두 찾습니다. 빈 키워드는 무시되어 화면이 열리지 않습니다.
- 결과를 고르면 안내 시작 화면을 거쳐 안내가 자동으로 시작됩니다. 콜백은 §5.2 표의 경로 요청과 같습니다. 안내 중에 고르면 기존 경로가 바로 바뀝니다.
- `getDestinationInfo`로 이름, 좌표, 주소, POI ID를 읽습니다. 주소로 고르면 이름에 주소 전체가 옵니다.
- 앱은 3초마다 목적지를 조회해 바뀌면 그룹 목적지로 가져옵니다. 같은 장소를 다시 고르면 경로를 다시 만드는 동안 비었다가 돌아오므로 그것으로 구분합니다.
- 검색을 열기 전에 현재 목적지를 읽습니다. 내비가 답하지 않으면 이미 있던 목적지를 고른 장소로 착각할 수 있어 검색을 시작하지 않습니다.
- 내비 안내 중에는 검색 목록이 아래로 밀려 표시됩니다.
- `getRecentDestinationInfo`도 동작하지만 쓰지 않습니다.

**그 밖에**

- 경로 요청은 내비 앱을 앞으로 가져옵니다. 앱은 아래쪽 아이콘으로 돌아옵니다. 위치 공유는 그동안에도 이어집니다.
- 앱 기본 창은 1682×1210dp, 왼쪽 패널은 853×1210dp입니다. 화면은 2560×1440, 160dpi입니다.
- `pleos.car.permission.NAVI_*`는 설치할 때 허용됩니다. `FUSED_LOCATION`은 실행 중 허용 창이 뜨고 문구는 "알 수 없는 작업"으로 나옵니다.
- 부팅 직후 에뮬레이터 시계가 틀렸다가 몇 분 뒤 맞습니다. 시작 전에 `adb shell date`를 봅니다.
- 소프트웨어 렌더링에서는 부하가 높을 때 시스템이 재시작한 적이 있습니다(워치독). 앱은 다시 열면 됩니다.
- 소프트웨어 렌더링에서 `wm density`로 밀도를 바꾼 뒤 에뮬레이터가 그래픽 오류(`invalid color buffer`)로 종료된 적이 있습니다. 밀도를 바꾸지 않습니다.
- Pleos는 앱을 사용자 10번으로 실행합니다. `run-as`에는 `--user 10`이 필요합니다.

## 6. Pleos 스모크 시험

`navi-smoke-debug.apk`로 확인합니다. 빌드 명령은 `.\gradlew.bat :navi-smoke:assembleDebug`입니다. 2026-11-07 결과는 §5.2에 있습니다. 환경이 바뀌면 같은 항목을 다시 봅니다.

```powershell
adb emu geo fix 126.9780 37.5665     # 경도가 먼저입니다
adb install -r navi-smoke-debug.apk
adb logcat -s MeetRouteSmoke
```

버튼마다 경로 계산이 끝날 때까지 30초쯤 기다립니다. 아래 순서로 누릅니다. 14·15번은 안내와 경유지가 있어야 하므로 표의 순서를 지킵니다.

| 순서 | 버튼 | 확인할 것 | 기록 |
|---|---|---|---|
| 0 | 앱 실행 | 권한 팝업과 허용 결과 | ☐ |
| 1 | 1 NaviHelper 초기화 | `OK NaviHelper 연결됨` | ☐ |
| 2 | 3 목적지 안내 시작 | `onRouteStarted`, `onRouteEnded` | ☐ |
| 3 | 4 안내 중 addWaypoint | `onWaypointChanged`, 내비 화면의 경유지 | ☐ |
| 4 | 6, 7, 8 정보 조회 | `remainTime`, `naviMode` | ☐ |
| 5 | 9 현재 위치(Navi) | 좌표. 시흥동에 고정인지 | ☐ |
| 6 | 14 두 번째 경유지 추가 | 경유지 2개 | ☐ |
| 7 | 15 첫 번째 경유지 삭제 | 뒤이어 6번으로 번호 변화 | ☐ |
| 8 | 10 경유지 전체 삭제 | 삭제 콜백 | ☐ |
| 9 | 5 목적지+경유지 동시 요청 | 내비 화면에 경유지 | ☐ |
| 10 | 11 안내 취소 | **앱 시험 전에 반드시 누릅니다.** 6.1의 안내 없음 조건이 됩니다 | ☐ |
| 11 | 12, 13 FusedLocation | `isSupported`, 1초 주기 값 | ☐ |
| 12 | 16 내비 장소 검색 | 검색 화면과 결과 | ☐ |

**확인할 때는 화면을 녹화합니다.** 이 영상을 11/15 기획서에 "실제 동작 확인"으로 넣습니다.

## 6.1 앱 통합 테스트

**준비.** `backend/.env`를 만듭니다. `.env.example`을 복사해 메모장으로 고칩니다. PowerShell의 `>`로 만들면 UTF-16이 되어 서버가 시작되지 않습니다. OSRM 공개 서버로 좌표가 나가므로 시뮬레이션 위치로만 씁니다. `estimate`로 두면 추천 1위가 강릉역이라 경유지 시험을 못 합니다.

```
ROUTING_PROVIDER=osrm
TOKEN_SECRET=<긴 임의 문자열>
```

`backend/.venv`는 만든 PC의 Python 경로에 묶입니다. 다른 PC에서는 새로 만듭니다. 서버는 `127.0.0.1`에 띄웁니다. 에뮬레이터는 `10.0.2.2`로 닿고, 같은 망의 다른 기기에는 열리지 않습니다.

```powershell
# 저장소의 MeetRoute 폴더에서. 다른 PC면 먼저 만듭니다
py -3.10 -m venv venv
venv\Scripts\python -m pip install -r backend\requirements.txt -r simulator\requirements.txt

# backend 폴더에서 서버 시작 (새 창)
..\venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# 확인: routing_provider=osrm, rest_areas=215
Invoke-RestMethod http://127.0.0.1:8000/health

adb shell date                         # 시계가 맞는지
adb emu geo fix 126.9780 37.5665       # 내 차량 출발지(서울). 경도가 먼저입니다
adb install -r app-debug.apk
cmd /c "adb logcat -v time -s MeetRouteNavi MeetRouteLocation MeetRouteDriving MeetRoute > logcat.txt"
```

`adb`는 `%LOCALAPPDATA%\Android\Sdk\platform-tools`에 있습니다. release APK는 HTTP 접속이 막혀 있으므로 `app-debug.apk`를 씁니다. 앱의 서버 주소는 기본값 `http://10.0.2.2:8000`입니다.

입력이 막히면 `adb shell cmd hmg_car_service --service drivingstate driving_regulation off`를 실행합니다. 기어와 속도는 Virtual Console로 바꿉니다. `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`을 엽니다.

| 순서 | 할 일 | 확인할 것 | 기록 |
|---|---|---|---|
| 1 | 앱 첫 실행 | 슬로건이 잘 보이는지 | ☐ |
| 2 | 그룹 생성 | 대기실 화면, 초대 코드 | ☐ |
| 3 | 공유 켜기 | 권한 3개 허용, 위치 출처 `Pleos FusedLocation`, 내 차량 `공유 중` | ☐ |
| 4 | 시뮬레이터 2대 참여 | 아래 명령. `시뮬레이션` 표시 | ☐ |
| 5 | 강릉역·휴식, 추천 | 1순위 평창(강릉), 3순위 문막(강릉). OSRM 기준 | ☐ |
| 5-1 | 목적지 검색 (별도 그룹) | 입력칸에 장소나 주소 입력, `내비에서 검색`, 결과 선택 뒤 그룹 목적지가 바뀌는지. 가까운 곳이면 목적지 합류만 나옵니다 | ☐ |
| 6 | 합류 지점 선택, 내비 등록 | `내비게이션에 등록했습니다`, 내비 화면 `경유지 1개 포함`. 내비가 앞으로 나오면 아래쪽 앱 아이콘으로 돌아옵니다 | ☐ |
| 7 | 같은 지점 다시 등록 | 내비가 바뀌지 않고 바로 끝나는지 | ☐ |
| 8 | 합류 지점 재선택 | 이전 지점이 사라지고 새 지점이 들어가는지, 안내 문구가 남지 않는지 | ☐ |
| 9 | 다른 목적지로 안내 중 등록 | `안내 중입니다… 바꿀까요?`, 승인하면 바뀌는지 | ☐ |
| 10 | ETA | `차량 내비` 표시, 내비 기준 남은 시간 | ☐ |
| 11 | 내 차량 이동 | `adb emu geo fix 128.452681 37.6055701`(평창). 다른 지점이면 그 좌표 | ☐ |
| 12 | 도착 | 시뮬레이션 차량 도착 뒤 `합류 완료` | ☐ |
| 13 | 공유 끄기 | 주행 현황에서 끌 수 있는지, 서버 좌표가 지워지는지 | ☐ |
| 14 | 네트워크 끊기, 재접속 | 재연결, 끊긴 동안의 변경 반영 | ☐ |
| 15 | 앱 종료, 이어가기 | 공유가 다시 켜지고 내비의 합류 지점을 다시 찾는지 | ☐ |
| 16 | 세션 종료 | 공유 중단, 시뮬레이터 종료 | ☐ |

```powershell
# 4번. MeetRoute 폴더에서. 앱에서 목적지를 정하기 전에 실행합니다
venv\Scripts\python simulator\vehicle_simulator.py --scenario simulator\scenarios\three_cars.json --invite-code <코드> --time-scale 30 --hold-until-selected
```

시뮬레이션 차량은 합류 지점을 고른 뒤 약 3분에 도착합니다. 시뮬레이터를 다시 실행하면 정원 초과로 실패합니다. 앱에서 그룹을 새로 만듭니다.

**영상에는 시뮬레이션임을 밝힙니다.** 내 차량 이동도 `geo fix`로 옮긴 값입니다. 내비 경로의 출발지는 §5.2대로 시흥동입니다.

**로그캣, 서버 로그, 내비 화면 녹화를 함께 남깁니다.**

### 막혔을 때

| 상황 | 대응 |
|---|---|
| OSRM 공개 서버 오류 | `.env`를 `ROUTING_PROVIDER=estimate`로 바꾸고 재시작합니다. 1순위가 강릉역이 되므로 2·3순위 휴게소를 고릅니다 |
| 앱이 서버에 못 붙음 | `adb reverse tcp:8000 tcp:8000` 뒤 서버 주소를 `http://127.0.0.1:8000`으로 바꿉니다(앱 통신은 미확인) |
| 추천 후보에 휴게소가 하나뿐 | 시뮬레이터가 이미 움직였습니다. `--hold-until-selected`로 새 그룹에서 다시 합니다 |
| 에뮬레이터 시스템 재시작 | 앱을 다시 열어 이어가기를 누릅니다 |

## 7. GPU PC 시험 결과 (2026-11-09)

RTX 3060 Ti PC에서 AEHD 가속과 `-gpu host`로 Pleos 에뮬레이터 v2.1.0을 실행했습니다. 부팅은 146초 걸렸습니다.

- 시연 5개 장면(합류, 검색, 운전자 확인, 목적지 없는 드라이브와 전기차, 그룹원 화면과 목적지 공개)이 모두 통과했습니다. 시험 내내 앱 프로세스가 바뀌지 않아 앱 종료는 없었습니다.
- **주행 중 제한:** Virtual Console이 `DDS ONLINE`이었고, 기어 D와 주행 속도를 넣으면 `CarUxRestrictionsManager`의 제한 상태가 켜져 설정이 숨겨지고 경고 문구가 나왔습니다. 정차하면 다시 꺼졌고, 켜짐과 꺼짐이 매번 로그에 남았습니다.
- 개발 PC에서는 Virtual Console이 `DDS OFFLINE`이라 기어와 속도를 바꿀 수 없었습니다(`503`).

## 8. 아직 검증되지 않은 것

아래는 **Pleos 런타임에서 확인하지 못했습니다.** 나머지는 §5.2와 §7에 실측 결과가 있습니다.

- 내비의 도착 콜백 `onWayPointArrived`, `onDestinationArrived`. Navi 위치가 고정이라 차량이 움직이지 않습니다
- 경로를 벗어난 상태에서 `requestReRoute`의 콜백. 경로 위에서는 콜백이 오지 않는 것만 확인했습니다
- 음성 안내. 앱은 Android 음성 엔진(`TextToSpeech`)으로 안내 문구를 말하도록 되어 있지만, GPU PC 녹화에서 안내 시점에 소리가 확인되지 않았습니다. Pleos 이미지에 한국어 음성 엔진이 없을 수 있습니다. Pleos TextToSpeech SDK가 지원되면 `VoiceOutput`의 새 구현으로 붙입니다

앱 재시작 뒤 목적지 합류는 저장된 경유지가 없어 ETA 추적이 복원되지 않습니다. 다시 등록하면 됩니다.
