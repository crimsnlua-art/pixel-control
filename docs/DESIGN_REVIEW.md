# 설계 검토 1회 (design-reviewer · 2026-11-12) — v0.2 대상

판정: 수정 후 진행. 전부 v0.3 에 반영.

반드시 고칠 것
1. ACCESS_MEDIA_LOCATION 없으면 GPS 가 지워진 바이트 → setRequireOriginal + 권한 필수. → §4
2. targetSdk 28+ 평문 HTTP 차단 → network_security_config, 위험 수용 명시. → §2
3. SSID 판정은 백그라운드 위치 권한 필요 + 불필요 → 비과금 와이파이 + 서명된 /status 로 대체. → §4
4. 경로 전환·드라이브 중복 사본 누수·동시 수신 → 사본 표, sha 검색, IN_DRIVE 는 LAN 재전송 안 함, sha 잠금. → §3·§7
5. 신호 A 오판(24시간 창·id 변경) → 파일 경로까지 확인, 1시간 창, VANISHED_UNKNOWN 다시 보내기. → §8
6. 백그라운드 실행·FGS 유형 미정 → 픽셀 dataSync 서비스 루프 + WifiLock/WakeLock, 받기는 API ≤ 29 만, 주력 폰은 FGS 없이 조각 워커. → §4·§9
7. 용량 모드 교착 → 실제 문턱 min(...), drainNow 자동. → §7
8. setConfig 명령 없음 → 추가, 상태에 config. → §7·§9
9. 테스트 경계 없음 → DriveApi/MediaSink/MediaSource/Clock/NetworkState. → §2
10. 부분 미디어 허용·기록 중 파일 → 전체 허용 확인, IS_PENDING 제외, 60초 안정. → §4

고치면 좋은 것: legacy 저장(.partial + rename) · appDataFolder · 프로젝트 키스토어 · 토큰 동의 필요 처리 · sha256Checksum·Range · ledger seq 빈틈 · 크기 SKIPPED · 경고를 주력 폰으로 · addContentUriTrigger · 재시도 정책·WAITING 상태 · 완료 기준 보강 → 전부 반영.

확인 필요 → DESIGN §13.
