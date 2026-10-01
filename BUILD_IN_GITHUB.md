# GitHub에서 APK 만들기

이 프로젝트는 GitHub Actions가 자동으로 설치용 APK를 빌드하도록 설정되어 있습니다.

## 최초 1회
1. GitHub에서 새 저장소를 만듭니다. 저장소 이름 예: `ScreenSketchSPen`.
2. 이 ZIP의 **폴더 안 파일 전체**를 저장소 루트에 업로드합니다.
   - `.github/workflows/build-apk.yml`도 반드시 포함되어야 합니다.
   - Android 앱 파일(`app`, `build.gradle.kts`, `settings.gradle.kts` 등)도 루트에 있어야 합니다.
3. 업로드/Commit이 끝나면 저장소의 **Actions** 탭을 엽니다.
4. `Build ScreenSketch APK` 워크플로를 선택합니다.
5. 자동 실행 중이면 완료될 때까지 결과를 확인합니다. 수동 실행하려면 `Run workflow`를 누릅니다.
6. 성공한 실행의 결과에서 `ScreenSketchSPen-v1.2-debug.apk`를 받습니다.

## 갤럭시탭 설치
1. APK를 갤럭시탭 S11 Ultra에 저장합니다.
2. 파일을 눌러 설치합니다.
3. 처음 한 번 Android가 요청하면 해당 브라우저/내 파일의 **알 수 없는 앱 설치 허용**을 켭니다.
4. `Screen Sketch S Pen` 실행 → `화면 위 표시 권한 허용` → `드로잉 시작`.

## 앱 사용
- DRAW ON: S펜으로 화면 위에 그리기
- TOUCH ON: 아래 앱(TradingView/PDF/지도)을 직접 조작
- S펜 측면 버튼: 누르고 있는 동안 지우개
- PEN / MARK / ERASE / LINE·ARROW·RECT·OVAL / COLOR / UNDO / REDO / SHOW-HIDE / CLEAR 지원

> 이 APK는 개발용 debug 서명 APK입니다. 개인 테스트 설치용으로 사용할 수 있습니다.
