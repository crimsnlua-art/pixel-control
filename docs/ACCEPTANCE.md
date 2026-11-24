# 완료 기준 — 1단계 (v0.3, 2026-11-12)

## 자동 테스트 (JVM · 가짜 DriveApi/MediaSink/MediaSource/Clock/NetworkState · Room 은 Robolectric 또는 in-memory)
1. 해시: 같은 내용 = 같은 SHA-256, 100MB 스트리밍 해시 = 한 번에 읽은 결과.
2. HMAC: 올바른 서명만 통과, 시각 ±5분 밖·다른 키·바뀐 경로·바뀐 메서드는 401.
3. 짝짓기 코드: 5분 지나면 무효, 1회용, 5회 틀리면 10분 잠금.
4. 이어받기: 조각 중간에 끊고 다시 begin → resume(offset), 끝까지 보낸 결과 SHA-256 = 원본. 잘못된 offset → 409. 해시 불일치 commit → 400 이고 부분 파일 없음.
5. 공간: 남은 < 크기 + 4GB → 507. 고온 중지 중 → 503.
6. 장부: 허용된 전이만. 한 번 오른 sha 는 새 DB 인스턴스(재시작)에서도 자동 재전송 대상 아님. `VANISHED_UNKNOWN` 의 "다시 보내기"만 예외.
7. 경로 판단: 비과금 와이파이 + 서명된 /status 성공 → LAN / 그 밖 → 드라이브 / 영상 + 과금망(설정 꺼짐) → WAITING / 로밍 → WAITING / 하루 한도 초과 → WAITING / queue+크기 > 계정 남은 − 2GB → WAITING(드라이브용량) + drainNow 1회 등록 / IN_DRIVE 는 LAN 으로 다시 안 보냄.
8. 수신 모드: 바로 / 용량(실제 문턱 = min(설정, 남은 − 3GB), 미만이면 안 받음, 이상이면 queue 를 비울 때까지) / sendNow·drainNow → 모드 무관 비울 때까지. 계정 남은 용량이 설정 용량보다 작아도 교착 없음(테스트로 확인).
9. 같은 sha 가 LAN 과 드라이브 양쪽(그리고 드라이브에 사본 2개)으로 들어옴 → 픽셀 파일 1개, 사본 표 2개, PHOTOS_CONFIRMED 뒤 드라이브 delete 2번 호출.
10. 포토 확인: 파일·MediaStore 둘 다 없음 + 재확인 + 버튼 후 1시간 안 → PHOTOS_CONFIRMED 이고 드라이브 삭제. 버튼 기록 없음/1시간 밖 → VANISHED_UNKNOWN 이고 드라이브 삭제 0회. MediaStore id 만 바뀜 → 사라짐 아님. 우리 앱이 지운 것 → 대상 아님.
11. **드라이브 delete 는 PHOTOS_CONFIRMED 항목의 사본에만 호출된다**(가짜 DriveApi 호출 기록으로 전 테스트에서 확인).
12. 드라이브 업로드: 같은 sha 가 queue 에 있으면 업로드 안 함. sha256Checksum 불일치 → 그 사본 삭제 후 재시도.
13. 기록 중 파일: IS_PENDING=1 또는 60초 안에 크기·수정 시각이 바뀐 파일은 해시·전송 안 함. 크기 초과 → SKIPPED.
14. ChargePolicy: 80/60 히스테리시스, 온도 > 40 중지 · < 38 재개, 같은 경고 30분 간격.
15. ledger 빈틈: seq 가 건너뛰면 다음 LAN 연결 때 /ledger 로 전체 맞춤.
16. LAN 서버 통합(JVM 로컬 소켓 + 파일 시스템 MediaSink): 짝짓기 → begin → 조각 2개 → commit → /ledger 에 PUBLISHED.

## 빌드
17. `gradlew assembleDebug` 성공(프로젝트 키스토어 서명), `gradlew testDebugUnitTest` 전부 통과, `lintDebug` 치명 오류 없음.

## 실기기 (사용자와 함께)
18. 짝짓기 후 집 와이파이에서 사진 10장 + 1GB 영상이 픽셀 Pictures/PixelRelay 에 도착. 원본은 `adb pull` 로 꺼낸 파일 기준 SHA-256 일치. 사진 1장은 픽셀 쪽 EXIF GPS 가 원본과 같음.
19. 전송 중 와이파이를 끊었다 붙이면 이어서 보냄.
20. 모바일 데이터에서 찍은 사진 → 주력 폰 앱의 드라이브 대기열에 보임 → 픽셀(바로 모드)이 받음.
21. 픽셀 포토 "기기 공간 확보"(앱 버튼으로 연 뒤) → 드라이브 경로로 온 시험 파일이 주력 폰에서 "포토 확인"으로 바뀌고 드라이브 사본이 지워짐. 포토 웹에서 원본 화질·계정 용량 미증가 확인.
22. 주력 폰 홈에 픽셀 상태가 LAN·드라이브 양쪽에서 보이고, 경고(예: 고온 흉내) 가 주력 폰 알림으로 옴.
23. 픽셀 재부팅 → 잠금 없이 서비스 자동 시작, 짝짓기 유지.
24. 픽셀 충전기 뺀 채 1시간 화면 꺼짐 → LAN 응답·드라이브 수신 정상(Doze 확인).
25. 촬영 날짜가 구글 포토에서 원본과 같음.

## 실행 방법
- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`, `pixel1_uploader/app` 에서 `./gradlew assembleDebug testDebugUnitTest lintDebug`.
- 설치: `adb -s <기기> install -r app/build/outputs/apk/debug/app-debug.apk`.
- 드라이브 실기 확인은 사용자가 OAuth 클라이언트(Android, `com.pixelrelay`, 프로젝트 키스토어 SHA-1)를 만든 뒤. 클라이언트 없이도 LAN 기능은 동작해야 함.
