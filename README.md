# Flock

**서로 다른 곳에서 출발한 여러 차량이, 목적지로 가는 길 위의 가장 합리적인 곳에서 만나도록 돕는 Pleos Connect 그룹 드라이브 앱**

현대자동차그룹 차량용 앱 개발 공모전 *Code your App into Pleos App Market* 출품작

- **합류 지점 추천:** 그룹 차량의 위치와 목적지를 바탕으로 경로 위 휴게소를 골라, 우회·대기 시간과 시설을 비교해 3곳을 추천합니다.
- **내비게이션 연동:** 고른 합류 지점을 Pleos 내비에 경유지로 등록하고, 내비의 남은 시간으로 그룹 도착 예정 시각을 공유합니다.
- **목적지 없는 드라이브:** 왕복 시간과 테마를 고르면 코스를 정해 주고, 목적지를 모두 모일 때까지 비공개로 둘 수 있습니다.
- **전기차 충전 연계:** 배터리 잔량을 읽어, 합류 지점에서 기다리는 시간을 충전 시간으로 쓰는 계획을 세웁니다.
- **주행 중 제한:** 차량의 주행 제한 신호를 받아 주행 중에는 설정 변경을 막습니다.

> 화면에 보이는 앱 이름은 Flock입니다. 개발 당시 이름(MeetRoute)이 패키지 이름(`com.meetroute.app`)과 일부 코드·문서에 남아 있습니다.

## APK 파일 위치

[`apk/강반석_Flock_release.apk`](apk/강반석_Flock_release.apk) (서명된 release 빌드)

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
|---|---|
| 차량 환경 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34) |
| PC | Windows 11, 하드웨어 가속(AEHD 또는 WHPX). GPU가 없으면 `-gpu swiftshader_indirect`로 실행할 수 있지만 느립니다 |
| 그룹 서버 | Python 3.10 이상. 앱과 같은 PC에서 실행합니다 |
| 앱의 서버 주소 | 기본값 `http://10.0.2.2:8000` (에뮬레이터에서 본 PC의 주소). 앱 첫 화면의 "서버 주소"에서 바꿀 수 있습니다 |
| 네트워크 | release 빌드는 로컬 주소(`10.0.2.2`, `127.0.0.1`, `localhost`)에만 HTTP를 허용하고, 그 밖의 서버는 HTTPS만 씁니다 |
| 권한 | Pleos 내비·배터리 권한은 설치할 때 허용됩니다. 위치 공유를 처음 켤 때 창이 세 번 뜨면 모두 허용합니다: 위치("앱 사용 중에만 허용"), 알림, Pleos 위치("알 수 없는 작업"으로 표시됨) |

**서버 설정 (`backend/.env`)**

`backend/.env.example`을 복사해 `backend/.env`로 만들고 아래 두 값을 바꿉니다. 메모장으로 저장합니다. PowerShell의 `>`로 만들면 UTF-16으로 저장돼 서버가 읽지 못합니다.

```
TOKEN_SECRET=<긴 임의 문자열>
ROUTING_PROVIDER=osrm
```

- `osrm`: 실제 도로망으로 시간을 계산합니다. 공개 OSRM 데모 서버를 쓰므로 **좌표가 외부로 전송됩니다.** 시뮬레이션 위치로만 시험해 주세요. 실시간 교통은 반영하지 않습니다.
- `estimate`: 외부 전송 없이 직선거리로 추정합니다. 결과에 "모의 데이터"로 표시되고, 추천 1순위가 목적지가 되는 경우가 많습니다.

## 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator **v2.1.0** (2026-10-19 배포, 시스템 이미지 `android-34 / pleos_car_emulator / x86_64`) |
| Android Emulator | 37.2.13 |
| 화면 | **2560 × 1440 px, 160 dpi**, 가로 고정 |
| 앱 창 | 기본 1682 × 1210 dp, 왼쪽 패널 853 × 1210 dp (에뮬레이터에서 측정) |
| 검증 | 개발 PC(소프트웨어 렌더링)와 GPU PC(RTX 3060 Ti, `-gpu host`)에서 시연 5개 장면 전체 통과 |

## 실행 방법

명령은 Windows PowerShell 기준이고, 저장소 루트에서 시작합니다.

**1. 그룹 서버 실행**

```powershell
cd backend
py -3.10 -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

다른 창에서 `Invoke-RestMethod http://127.0.0.1:8000/health`를 실행해 `rest_areas=215`가 나오면 준비된 것입니다.

**2. 에뮬레이터에 앱 설치**

```powershell
# adb는 %LOCALAPPDATA%\Android\Sdk\platform-tools에 있습니다
adb install -r apk\강반석_Flock_release.apk
adb emu geo fix 126.9780 37.5665      # 내 차량 위치: 서울 (경도가 먼저)
```

**3. 그룹 만들기**

앱을 열고 그룹을 만든 뒤 위치 공유를 켭니다. 화면에 초대 코드가 표시됩니다.

**4. 가상 차량 참여 (새 창, 저장소 루트)**

차량 한 대로 여러 대 그룹을 시험할 수 있도록 다른 차량은 시뮬레이터가 맡습니다. 앱 화면에는 "시뮬레이션"으로 표시됩니다.

```powershell
backend\.venv\Scripts\python -m pip install -r simulator\requirements.txt
backend\.venv\Scripts\python simulator\vehicle_simulator.py --scenario simulator\scenarios\three_cars.json --invite-code <초대 코드> --time-scale 30 --hold-until-selected
```

| 시나리오 | 내용 |
|---|---|
| `three_cars.json` | 서울·수원·인천 → 강릉 |
| `three_cars_ev.json` | 위와 같고 수원 차량이 배터리 35%인 전기차 (충전 연계 시험) |
| `two_cars_busan.json` | 대전·천안 → 부산 |
| `three_cars_yeosu.json` | 대전·전주·광주 → 여수 |

**5. 시험 흐름**

1. 목적지를 강릉역으로 정하고 추천을 받습니다. 합류 지점 3곳과 목적지 직행이 비교되어 나옵니다.
2. 합류 지점을 고르고 내비에 등록합니다. Pleos 내비가 앞으로 나오면 아래쪽 앱 아이콘으로 돌아옵니다.
3. 가상 차량은 약 3분 뒤 합류 지점에 도착합니다. 내 차량은 `adb emu geo fix <경도> <위도>`로 합류 지점에 옮기면 도착 처리되고, 모두 도착하면 "합류 완료"가 됩니다.
4. 목적지 없는 드라이브는 대기실에서 왕복 시간과 테마를 고르고, 비공개를 켜면 모두 모일 때까지 그룹원에게 목적지가 가려집니다.
5. 주행 중 제한은 Virtual Console에서 시험합니다. `adb forward tcp:8088 tcp:8088` 뒤 브라우저로 `http://127.0.0.1:8088/`을 열고, 기어를 D로 두고 속도를 올리면 설정이 숨겨집니다.

**알아 둘 점**

- Pleos 에뮬레이터의 내비 위치는 성남시 시흥동에 고정되어 있어, 내비 경로는 항상 그곳에서 출발합니다. 앱의 위치 공유와 도착 판정은 `geo fix`를 따르는 FusedLocation 값을 씁니다.
- 내 차량은 에뮬레이터라 스스로 움직이지 않습니다. 위치를 옮기려면 `geo fix`를 씁니다.
- 시뮬레이터를 다시 실행하면 이전 가상 차량이 자리를 차지해 정원 초과가 됩니다. 앱에서 그룹을 새로 만듭니다.

## 소스에서 빌드하기

Android Studio에서 `android-app` 폴더를 엽니다. 명령으로 빌드할 때는 `JAVA_HOME`을 Android Studio의 `jbr` 폴더로 지정합니다.

```powershell
cd android-app
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug                     # 개발용 (모든 HTTP 허용)
.\gradlew.bat :app:assembleRelease "-Pflock.signing=<keystore.properties 경로>"
```

서명 키는 저장소에 넣지 않습니다. `keystore.properties`에는 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 적습니다. `-Pflock.signing` 없이 빌드하면 서명되지 않은 release APK가 만들어집니다. 앱의 기본 서버 주소는 `-Pmeetroute.serverUrl=https://...`로 바꿀 수 있습니다.

## 자동 시험

```powershell
cd backend; .\.venv\Scripts\python -m pytest                       # 서버·추천 엔진·WebSocket (214건)
cd ..; backend\.venv\Scripts\python -m pytest tests                # 실제 서버 + 시뮬레이터 통합 (11건)
cd android-app; .\gradlew.bat :app:testDebugUnitTest               # 차량 앱 (188건)
```

## 저장소 구조

```
android-app/   차량 앱 (Kotlin, Jetpack Compose). navi-smoke는 Pleos SDK 동작 확인용 시험 앱
backend/       그룹 서버와 추천 엔진 (Python FastAPI, WebSocket, SQLite)
simulator/     가상 차량 시뮬레이터, 기준선 비교 실험
data/          휴게소 215곳, 드라이브 명소 100곳과 변환 스크립트
tests/         서버·시뮬레이터 통합 시험
docs/          설계와 구현 문서
apk/           제출용 release APK
```

## 문서

- [Pleos SDK 연동과 에뮬레이터 실측 기록](docs/sdk-integration.md)
- [추천 알고리즘](docs/algorithm.md) · [기준선 비교 실험](docs/experiments.md)
- [API](docs/api.md) · [아키텍처](docs/architecture.md) · [화면 흐름](docs/ui-flow.md)
- [초기 설계 문서](docs/design.md)

## 데이터 출처

- 고속도로 휴게소: 한국도로공사 휴게소 정보 (공공데이터포털). 자세한 내용은 [data/README.md](data/README.md)
- 드라이브 명소 좌표: © OpenStreetMap contributors (ODbL)
- 도로 경로 시간: OSRM (OpenStreetMap 기반)
