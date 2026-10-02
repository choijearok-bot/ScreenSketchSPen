# Screen Sketch S Pen v1.5.1

Galaxy Tab / S Pen용 화면 오버레이 드로잉 앱.

## 핵심 동작
- PEN ON/OFF는 펜 입력만 전환합니다. 툴바와 기존 그림은 유지됩니다.
- EXIT APP만 전체 오버레이 서비스를 종료합니다.
- 그림/도구/툴바 위치/PEN 상태는 자동 보존됩니다.
- v1.4 자동저장 그림이 있으면 v1.5 최초 실행 시 자동 마이그레이션합니다.

## v1.5 기능
- LASSO: 영역 선택 후 이동, 오른쪽 아래 핸들 드래그로 크기 조절
- LOCK: 툴바 위치 잠금
- 원형 최소화: HIDE 버튼을 누르면 작은 ✎ 버튼만 유지
- ★ PENS: P1~P5 즐겨찾기. 탭=적용, 길게=현재 펜 저장
- 최근 사용 색상 4개를 색상 팔레트 상단에 표시
- WORK: 탭=작업파일(.ssk) 저장, 길게=가장 최근 작업파일 불러오기
- RECOVER: 최대 10개의 자동저장 이력에서 한 단계씩 복구
- 화면 회전/해상도 변경 시 기존 그림 좌표 자동 보정

## 기존 기능
- 펜 / 형광펜 / 지우개 / S Pen 버튼 임시 지우개
- 직선 / 화살표 / 사각형 / 타원
- Undo / Redo / 전체삭제 2회 확인
- PNG 저장 / PDF 저장 / 시스템 인쇄
- 완전 종료 2회 확인 + 알림창 종료

## WORK 파일 위치
Android 10 이상: `Downloads/ScreenSketch/ScreenSketch_Work_YYYYMMDD_HHMMSS.ssk`

## GitHub Actions
`.github/workflows/build-apk.yml`가 `ScreenSketchSPen-v1.5.1-debug.apk`를 생성합니다.


## v1.5.1 수정
- 세로 1열 툴바를 2열 컴팩트 툴바로 변경
- PEN ON/OFF와 EXIT APP을 상단에 배치
- 툴바의 실제 높이를 기준으로 화면 밖 이동을 자동 차단
- 가로/세로 회전 후에도 툴바가 화면 안으로 자동 보정
