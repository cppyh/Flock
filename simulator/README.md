# 시뮬레이터

가상 차량으로 다차량 흐름을 재현합니다. 앱 화면에는 **시뮬레이션** 표시가 붙습니다.

## 실행

서버를 먼저 띄운 뒤(`backend` 폴더에서 `uvicorn app.main:app --port 8000`) 저장소 루트에서 실행합니다.

```powershell
# 시연: 앱이 만든 그룹에 차량 B·C 참여 (시나리오의 첫 차량은 앱이 맡습니다)
python simulator\vehicle_simulator.py --scenario simulator\scenarios\three_cars.json --invite-code ABC234 --time-scale 30 --hold-until-selected

# 시뮬레이터가 그룹까지 직접 생성 (차량 3대 모두 가상)
python simulator\vehicle_simulator.py --scenario simulator\scenarios\three_cars.json
```

| 옵션 | 설명 |
|---|---|
| `--server` | 서버 주소. 기본값 `http://127.0.0.1:8000` |
| `--invite-code` | 앱이 만든 그룹에 참여 |
| `--time-scale` | 이동 속도 배율. 기본값 1은 실제 속도라 1시간 넘게 걸립니다. 시연은 `30`이면 합류 지점 선택 뒤 약 3분에 도착합니다 |
| `--hold-until-selected` | 합류 지점이 정해질 때까지 출발지에 머뭅니다. 시연 때 추천 결과가 흔들리지 않습니다 |
| `--interval` | 위치 전송 주기(초) |
| `--duration` | 지정한 시간(초) 뒤 종료 |

차량은 목표 지점으로 **직선 이동**합니다. 실제 도로를 따르지 않으므로 시연용입니다. 합류 지점이 정해지면 그곳으로, 그전에는 목적지로 움직입니다(`--hold-until-selected`면 머뭅니다). 연결이 끊기면 재접속해 최신 그룹 상태를 다시 받습니다.

`--hold-until-selected` 없이 배율을 높이면 추천 전에 차량이 이미 멀리 가 있어 결과가 달라집니다. 시뮬레이터를 끄고 다시 실행하면 이전 차량이 그룹 자리를 차지하고 있어 참여하지 못합니다. 앱에서 그룹을 새로 만듭니다.

## 시나리오

| 파일 | 내용 |
|---|---|
| `scenarios/three_cars.json` | 서울·수원·인천 → 강릉, 휴식 |
| `scenarios/three_cars_ev.json` | 위와 같고 수원 차량이 배터리 35%인 전기차. 충전 연계 시연용 |
| `scenarios/two_cars_busan.json` | 대전·천안 → 부산, 식사 |
| `scenarios/three_cars_yeosu.json` | 대전·전주·광주 → 여수, 같이 출발 |
| `scenarios/experiments.json` | 기준선 비교용 12개 조합 |

차량에 `"battery_percent"`를 넣으면 전기차가 됩니다. 달린 거리만큼 배터리가 줄고(0.18kWh/km, 77.4kWh), 충전기가 있는 합류 지점에 도착해 기다리면 100kW로 80%까지 찹니다. 위치와 함께 `battery_percent`를 보내므로 앱의 차량 목록과 추천의 충전 계획에 나타납니다.

비공개 드라이브의 그룹원으로 참여하면 목적지가 오지 않으므로(`destination: null`) 합류 지점이 정해질 때까지 출발지에 머뭅니다.

## 다른 도구

- `run_experiments.py`: 기준선 B0·B1·B2·B3·Ours를 비교해 `docs/experiments-results.md`를 만듭니다.
- `run_sensitivity.py`: 가중치별 추천 결과를 비교합니다.
- `record_demo.py`: 실제 공공데이터와 도로망으로 추천을 계산해 앱의 오프라인 시연 데이터를 만듭니다.

세 스크립트는 `backend/.venv`의 Python으로 실행합니다.
