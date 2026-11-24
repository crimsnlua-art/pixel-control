# 픽셀중계(PixelRelay) 설계서 v0.3 (2026-11-12)

v0.2 → v0.3: 설계 검토(`DESIGN_REVIEW.md`) 반드시 1~10 반영, 좋은 것 1·2·3·4·5·6·7·8·9·10·11 반영.
근거 조사: `../조사보고_20261111.md` + 검색 기록 `~/.claude/state/gpt_runs/20261111_*_search`.

## 0. 확정된 결정 (사용자 2026-11-11)
- 주력 폰 = 안드로이드(현재 기기 Galaxy S23 Ultra SM-S918N, Android 17 / API 37). 픽셀1 = Android 10 / API 29, 32GB.
- 같은 구글 계정으로 두 폰 로그인.
- 전송: 집 와이파이 = 픽셀로 직접(LAN) · 그 밖 = 구글 드라이브를 중계 저장소로. 서버·VPN 없음.
- 드라이브 파일 삭제 = 구글 포토 업로드 확인 뒤.
- 픽셀이 드라이브에서 받는 때 = 사용자가 고름: 바로 / 대기열이 설정 용량(기본 10GB) 이상 / "지금 보내기".
- 루팅 안 함. 충전 보호 = 스마트 플러그(제품은 나중). 1단계에는 플러그 제품 코드 없음.

## 1. 목표 / 비목표
목표
1. 주력 폰에서 찍은 사진·영상(원본 바이트 그대로, GPS 포함)이 자동으로 픽셀1에 도착한다.
2. 최종 업로드는 픽셀1의 공식 구글 포토 앱만 한다. 우리 앱은 구글 포토에 직접 올리지 않는다.
3. 포토 백업이 확인된 파일만 픽셀·드라이브에서 지운다. 시간 추정 삭제 금지.
4. 주력 폰 앱 하나로 상태 확인·명령·경고 수신(집 밖에서도).
5. 한 번 보낸 파일은 자동으로 다시 보내지 않는다(예외: 사용자가 `VANISHED_UNKNOWN` 항목에 "다시 보내기"를 누름). 픽셀·드라이브 쪽 삭제는 주력 폰 원본을 지우지 않는다.

비목표(1단계): 아이폰, 비공식 업로드, 기존 유료 용량 회수, Play 배포, 플러그 제품 제어, 접근성 자동화(2단계), 주력 폰 원본 삭제·기존 사진 보내기(3단계).

## 2. 앱 구조
- 한 APK, 첫 실행에 역할 선택: 보내기 / 받기. **받기는 API 29 이하 기기에서만 고를 수 있다**(14+ 의 포그라운드 서비스 제한 때문에 상시 서버 불가).
- Kotlin + Jetpack Compose, Room, WorkManager, OkHttp, NanoHTTPD, DataStore, Google Drive REST v3(OkHttp 직접 호출), Google Identity `AuthorizationClient`.
- minSdk 29, targetSdk 36, compileSdk 36. 패키지 `com.pixelrelay`. 서명 = 프로젝트 전용 키스토어(`keystore/pixelrelay.jks`, debug·release 모두 이 키). 비밀번호는 `keystore.properties`(git 제외).
- 패키지 나눔: `core/`(해시·장부·프로토콜·설정·시계) · `sender/` · `receiver/` · `drive/` · `charge/` · `ui/`.
- **바꿔 끼울 수 있는 경계(테스트용)**: `DriveApi`, `MediaSink`(픽셀 저장: begin/append/commit/exists), `MediaSource`(주력 폰 읽기), `Clock`, `NetworkState`, `PlugController`. 실제 구현과 JVM 테스트용 가짜 구현.
- 네트워크 보안 설정: 평문 HTTP 를 base-config 에서 허용(LAN 서버 IP 가 고정 도메인이 아니라서). 드라이브는 HTTPS. **집 LAN 안에서 사진 내용과 짝짓기 키가 평문으로 흐르는 것은 받아들인다**(위험 수용, 사용자 고지).

```
[주력 폰: 보내기]                                      [픽셀1: 받기]
 새 사진 감지 → 보내기 장부(Room)                       포그라운드 서비스(dataSync, 상시)
   │ 비과금 와이파이 + 서명된 /status 응답 ──LAN──▶     LAN 서버 → 받기 장부
   │ 그 밖 ──▶ 드라이브 appDataFolder/queue ──▶        드라이브 수신 루프(모드)
 상태 화면·경고 알림 ◀── LAN /status·/ledger            MediaStore 공개(Pictures/PixelRelay)
                     ◀── 드라이브 status.json ◀────    상태 보고 · 포토 확인 감시
 명령 ── LAN /command 또는 드라이브 commands/ ──▶       명령 처리
```

## 3. 파일 식별·장부
- 키 = 원본 바이트 SHA-256(소문자 hex).
- 보내기 장부 상태:
  - `WAITING(사유)` — 사유: 기록중, 권한필요, 네트워크조건, 하루한도, 드라이브용량, 픽셀공간, 크기초과
  - `SENDING_LAN` → `RECEIVED`
  - `UPLOADING_DRIVE` → `IN_DRIVE` → `RECEIVED`
  - `RECEIVED` → `PHOTOS_CONFIRMED`
  - `VANISHED_UNKNOWN`(픽셀 상태를 따라 표시)
  - `FAILED(사유, 시도 횟수)`, `SKIPPED(사유: 제외폴더|크기초과|원본없음)`
- 경로 규칙: `SENDING_LAN` 중 LAN 이 끊기면 → `WAITING(네트워크조건)` 으로 돌아가 다음 판단 때 다시 고름(LAN offset 은 픽셀이 기억). **`IN_DRIVE` 가 된 파일은 LAN 으로 다시 보내지 않는다**(픽셀이 드라이브에서 받음). `UPLOADING_DRIVE` 중 집에 오면 업로드를 마저 끝낸다(경로 혼선 방지).
- 재시도: 실패 시 1분·5분·30분·2시간·이후 6시간 간격, 최대 20회 → `FAILED`. 원본이 사라졌으면 `SKIPPED(원본없음)`.
- 받기 장부: sha, 원래 이름, 크기, 촬영 시각, mime, 들어온 경로, 파일 경로, MediaStore id, 상태, 각 상태 시각, 변화 일련번호(seq, 단조 증가).
- **드라이브 사본 표(받기 쪽)**: (sha, driveFileId) 여러 개. 픽셀이 queue 를 볼 때마다 sha 로 대조해 붙인다.
- 받기 상태: `RECEIVING → PUBLISHED → PHOTOS_CONFIRMED → CLEANED`(드라이브 사본 모두 삭제 완료) / `VANISHED_UNKNOWN` / `SKIPPED(크기초과)`.
- 같은 sha 동시 수신: 받기 쪽 sha 단위 잠금. 이미 받는 중이면 다른 경로는 `busy`(LAN 423) 또는 드라이브는 이번 회차 건너뜀. 이미 `PUBLISHED` 이후면 `already` 이고 드라이브 사본만 표에 붙임.

## 4. 주력 폰: 발견·읽기·경로
- 권한: `READ_MEDIA_IMAGES`·`READ_MEDIA_VIDEO` **전체 허용** 필수(부분 허용이면 홈에 경고하고 자동 감지 멈춤), `ACCESS_MEDIA_LOCATION` 필수(없으면 `WAITING(권한필요)`), `POST_NOTIFICATIONS`. 위치 권한·SSID 사용 안 함.
- 읽기: `MediaStore.setRequireOriginal(uri)` 로 연 원본 스트림만 사용(GPS 가림 방지).
- 감시: 고른 폴더(기본 `DCIM/Camera`), 제외 폴더(기본 Screenshots·KakaoTalk 등). 역할 지정 시각 이후 파일만.
- 감지: WorkManager `addContentUriTrigger`(앱 꺼져도 깨어남) + 15분 주기 스캔.
- 안정 조건: IS_PENDING=1 제외. 크기·DATE_MODIFIED 가 60초 동안 그대로인 파일만 해시.
- 크기: 사진 > 200MB, 영상 > 10GB → `SKIPPED(크기초과)`.
- 경로 판단(전송 시점마다):
  1. 현재 망이 비과금(UNMETERED) 와이파이이고 픽셀 LAN 주소(NSD `_pixelrelay._tcp` 또는 마지막 IP)에 서명된 `GET /status` 성공 → LAN.
  2. 그 밖 → 드라이브. 조건: 사진 = 모바일 데이터 허용, 영상 = 와이파이에서만(설정), 로밍 금지, 하루 데이터 한도(기본 2GB).
  3. 드라이브 용량: queue 합계 + 파일 크기 > 계정 남은 용량 − 2GB → `WAITING(드라이브용량)` + `commands` 에 `drainNow` 자동 등록(1시간에 1번까지).
- 실행 방식: 포그라운드 서비스 없음. 워커 1회는 최대 약 5분 분량(조각 8MB 단위) 보내고 끝, 남으면 다음 워커 예약. LAN offset·드라이브 재개 업로드 세션 URI 를 장부에 저장해 프로세스가 죽어도 이어 보냄.
- Android 16+ 로컬 네트워크 권한: 해당 권한이 기기에 있으면 LAN 사용 전에 요청, 거부 시 LAN 없이 드라이브만(구현 때 실기 확인).

## 5. LAN 프로토콜 (픽셀 서버, 포트 47321, HTTP)
- 짝짓기: 픽셀 화면의 6자리 코드(5분 유효·1회용·5회 틀리면 10분 잠금) → `POST /pair {code, deviceName}` → 32바이트 키(base64). 이후 모든 요청 헤더 `X-PR-Time`(epoch ms), `X-PR-Sig = hex(HMAC-SHA256(key, METHOD\npath?query\ntime))`. 시각 차 ±5분 넘으면 401. 조각 본문은 서명하지 않음(무결성은 commit 의 전체 SHA-256).
- `GET /status` → 상태 객체(§9 상태 형식).
- `POST /files/{sha}/begin {name,size,takenAt,mime}` → `{state:"new"|"resume","offset":N}` 또는 `{state:"already"}`; 423 busy; 507 공간 부족(남은 < 크기 + 여유); 503 고온 중지.
- `PUT /files/{sha}?offset=N` 조각(≤ 8MB). offset 불일치 → 409 `{offset}`.
- `POST /files/{sha}/commit` → 전체 SHA-256 재계산. 일치 → 공개 → `{state:"published"}`; 불일치 → 부분 파일 삭제, 400.
- `GET /ledger?since=seq&limit=500` → `{items:[{sha,state,at,seq}], nextSeq, hasMore}`.
- `POST /command {type, ...}` — §7 명령 목록.

## 6. 픽셀 저장 (Android 10 전용 방식)
- `requestLegacyExternalStorage="true"` + `WRITE_EXTERNAL_STORAGE(maxSdkVersion=29)`.
- 받는 중: `Pictures/PixelRelay/.partial/{sha}.part` (폴더에 `.nomedia`). 직접 append.
- commit: 해시 확인 → 같은 볼륨 안 rename 으로 `Pictures/PixelRelay/{원래 이름}`(이름 충돌 시 `이름_sha앞8자리`) → `setLastModified(takenAt)` → `MediaScannerConnection.scanFile` → MediaStore id 기록 → `PUBLISHED`.
- 공간 판정: 남은 < 크기 + 4GB 면 거절(복사 없음이라 2배 불필요). 크기 > 남은 − 4GB 인 파일은 영원히 못 받으므로 보내기 쪽에 `SKIPPED(크기초과)` 신호.

## 7. 드라이브 중계
- 범위: `https://www.googleapis.com/auth/drive.appdata` (**appDataFolder**). 같은 OAuth 클라이언트·같은 계정이면 두 기기가 같은 숨은 폴더를 공유. 사용자가 드라이브 화면에서 실수로 지울 수 없음. 용량은 계정에서 차감.
- 이름 규칙(폴더 없이 이름 접두어): `queue/{sha}.{ext}`, `status.json`, `commands/{uuid}.json`. appProperties: {kind, sha, name, takenAt, mime, size}.
- 보내기:
  - 업로드 전 `q = appProperties has {key='sha' and value='…'}` 로 검색 → 있으면 업로드 없이 `IN_DRIVE`.
  - 재개 가능 업로드(세션 URI 장부 저장), 끝나면 응답의 `sha256Checksum` 과 대조 → 일치해야 `IN_DRIVE`, 불일치면 그 파일 삭제 후 재시도.
- 받기(픽셀 수신 루프):
  - queue 목록마다 sha 대조: 받기 장부에 있으면 내려받지 않고 사본 표에만 붙임. 없으면 내려받기(`Range` 이어받기, `.partial` 사용) → commit 과정 동일.
  - 모드: **바로**(2분마다 목록) · **용량**(10분마다 목록, 실제 문턱 = `min(설정 용량, 계정 남은 − 2GB − 1GB)`, 문턱 넘으면 queue 를 비울 때까지 받음) · **명령**(`sendNow`/`drainNow` 받으면 모드 무관 queue 를 비울 때까지 받음).
  - 해시 불일치: 3회까지 다시 받기, 이후 `FAILED` 표시(드라이브 사본은 남김).
- **삭제 규칙(유일)**: 받기 장부 항목이 `PHOTOS_CONFIRMED` 가 되면 그 sha 의 드라이브 사본 전부 `files.delete`(영구). 모두 성공하면 `CLEANED`. 다른 어떤 경로도 queue 파일을 지우지 않는다(해시 불일치로 보내기 쪽이 방금 만든 사본을 지우는 경우 제외).
- 명령: `commands/{uuid}.json` 을 주력 폰이 만들고, 픽셀이 처리 후 결과를 status 에 남기고 그 명령 파일 삭제. 종류: `sendNow`, `drainNow`, `setConfig{queueMode, thresholdBytes, reserveBytes}`, `cleanupOpened`(공간 확보 실행 기록, 픽셀 화면 전용이라 원격 아님 — 원격 불가).
- 주력 폰 "지금 보내기" = ① 주력 폰 대기 파일을 데이터 조건 무시하고 지금 보냄(영상+모바일 데이터면 확인 창) ② 픽셀에 `sendNow`.
- 상태 보고: 픽셀이 5분마다 + 상태 변화 때(최소 1분 간격) `status.json` 덮어쓰기. 변화 목록은 seq 붙여 최근 500건. 주력 폰은 받은 seq 에 빈틈이 있으면 다음 LAN 연결 때 `/ledger?since=` 로 전부 맞춤.
- 토큰: `AuthorizationClient.authorize`. 결과가 사용자 동의 필요(PendingIntent)면 알림 띄우고 드라이브 경로 멈춤. 401 → 토큰 다시 받아 1회 재시도.
- OAuth 클라이언트: Android 유형, 패키지 `com.pixelrelay`, 프로젝트 키스토어 SHA-1. 동의 화면 게시 상태는 '프로덕션'(비민감 범위 drive.appdata, 확인 필요 §13-2).

## 8. 포토 백업 확인 (픽셀)
- 신호 A(1단계 유일한 삭제 근거): `PUBLISHED` 항목이 ① MediaStore 에 없고 ② 실제 파일 경로에도 없고 ③ 1분 뒤 재확인해도 없음 → "사라짐".
  - 사라진 시각이 픽셀 앱의 "포토 공간 확보 열기" 버튼을 누른 뒤 **1시간 안**이면 `PHOTOS_CONFIRMED`.
  - 그 밖은 `VANISHED_UNKNOWN`(드라이브 사본 유지).
- 우리 앱이 지운 파일(해시 불일치 등)은 대상 아님.
- MediaStore id 만 바뀌고 파일이 있으면 → 새 id 로 갱신(사라짐 아님).
- `VANISHED_UNKNOWN` 화면(픽셀·주력 폰): 각 항목 "포토 확인됨으로 확정"(경고: 포토 웹에서 원본 화질로 있는지 먼저 확인) / "다시 보내기"(주력 폰 원본에서, 장부 재전송 예외).
- 감지: ContentObserver + 10분 주기 대조.
- 초기 설정 안내: 픽셀 "설정 > 저장용량 > 스마트 저장용량" 끄기(켜져 있으면 버튼 기록 없이 사라짐 → `VANISHED_UNKNOWN` 다발).
- 신호 B(포토 "백업 완료" 문구)는 2단계.

## 9. 픽셀 상시 운영
- 포그라운드 서비스: 유형 `dataSync`, 권한 `FOREGROUND_SERVICE`(+ `FOREGROUND_SERVICE_DATA_SYNC` 선언). BOOT_COMPLETED 로 시작. 모든 주기 작업(드라이브 루프, 상태 보고, 배터리 기록, 포토 확인 대조)은 서비스 안 코루틴 루프.
- `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` 상시, 전송·폴링 중 부분 `WakeLock`. 배터리 최적화 제외 요청. 충전 안 할 때(배터리 구동) 폴링 간격 2배.
- 화면 잠금 '없음', 스마트 저장용량 끄기, 포토 폴더 백업 켜기 → 초기 설정 안내 화면 체크리스트.
- 상태 형식(`/status`·`status.json` 공통): `{freeBytes, batteryPct, charging, tempC, counts{published, confirmed, vanished, failed}, lastPhotosActivityAt, config{queueMode, thresholdBytes, reserveBytes}, warnings[{code, since}], seq, appVersion, reportedAt}`.
- 경고 코드: `PHOTOS_STALLED`(PUBLISHED 있는데 6시간 신호 없음), `LOW_SPACE`(남은 < 4GB), `HIGH_TEMP`(> 40℃), `UNPLUG_SUGGESTED`(≥ 80% 충전 중), `PLUG_SUGGESTED`(≤ 60% 미충전), `LOW_BATTERY`(≤ 20%), `DRIVE_AUTH`. 주력 폰은 새 경고를 알림으로 띄움.

## 10. 충전 보호 (1단계)
- 배터리 %, 충전 여부, 온도 1분마다 기록.
- 온도 > 40℃ → 받기 중지(LAN 503, 드라이브 수신 멈춤), < 38℃ → 재개.
- `ChargePolicy`: 80% 이상 → 끄기 판단, 60% 이하 → 켜기 판단, 사이는 유지. 1단계에서는 `NoPlug` → 경고만(`UNPLUG_SUGGESTED`/`PLUG_SUGGESTED`, 같은 경고 30분 간격).

## 11. 화면 (Compose, 한글)
보내기: 홈(상태별 개수, 픽셀 남은 공간·배터리·온도·충전·마지막 소식 시각과 경로, 경고, "지금 보내기"), 파일 목록(상태 필터), 원인 불명 목록, 설정(감시·제외 폴더, 영상 데이터 허용, 하루 한도, 픽셀 수신 모드·용량 → `setConfig`), 짝짓기, 구글 계정 연결, 권한 안내.
받기: 상태 화면(서버, 받은 수, 남은 공간, 배터리·온도, 포토 확인·대기 수, "포토 공간 확보 열기", "지금 받기"), 짝짓기 코드, 설정(수신 모드·용량·여유), 원인 불명 목록, 초기 설정 체크리스트.

## 11-1. 앱 안 설정 마법사 · 무료 확인 시험 (사용자 요청 2026-11-12, 1단계-b)
- 첫 실행 마법사(역할별 단계 화면, 끝난 단계 체크, 나중에 설정에서 다시 열기):
  - 픽셀: 권한 → 배터리 최적화 제외 → 화면 잠금 '없음' 안내(설정 화면 바로가기) → 스마트 저장용량 끄기 안내(바로가기) → 구글 포토 열기 + "PixelRelay 폴더 백업 켜기" 안내(폴더는 앱이 미리 만들고 시험 사진 1장 넣어 포토 목록에 나타나게) → 구글 계정 연결 → 짝짓기 코드.
  - 주력 폰: 권한(사진 전체 허용·위치 정보·알림) → 배터리 최적화 제외 → 구글 포토 백업 끄기 안내(주력 폰이 먼저 올리면 용량 차감) → 구글 계정 연결 → 픽셀 짝짓기.
  - 바로가기 인텐트가 기기에서 안 열리면 글 안내로 대체.
- "무료 업로드 확인 시험"(주력 폰에서 시작, 0단계 자동화):
  1. 드라이브 `about.get` 으로 계정 사용량 기록(시작값).
  2. 주력 폰이 시험 사진 3장(그중 1장 위치 포함, 앱이 카메라 앱 열어 찍게 하거나 최근 사진 중 고르게)을 일반 경로로 픽셀에 보냄.
  3. 픽셀 화면에 "포토 백업 완료되면 눌러 주세요" + 포토 열기. 주력 폰엔 진행 표시.
  4. 완료 신호 후(그리고 10분 뒤 한 번 더) 사용량 재측정 → 시험 파일 합계만큼 늘었으면 "용량 차감됨(무료 아님)", 그대로면 "무료 확인". 반영 지연 가능 문구.
  5. 이어서 "공간 확보 시험": 픽셀이 포토 공간 확보 열기 → 시험 파일이 사라지면 "포토 확인 방식 정상", 남으면 "최근 항목은 안 지움 → 운영 방식 조정 필요" 표시 + 결과를 장부에 기록.
  6. 날짜·위치는 앱이 클라우드 쪽을 못 읽으므로(Photos API 제한) "포토에서 이 사진 열어 날짜·위치 확인" 안내만.
- 구글 계정 연결 = 앱 안 버튼 하나(계정 고르기 → 허용). 단, 앱 자체를 구글에 등록(OAuth 클라이언트)하는 것은 앱 주인이 한 번만 콘솔에서 해야 함(앱 안에서 불가). 등록이 안 됐으면 연결 버튼에 "앱 등록 필요 — 개발자 설정" 안내, LAN 기능은 그대로 동작.

## 12. 단계
- 0단계(사용자, 코드와 병행): 갤럭시 사진을 픽셀 `Pictures/PixelRelay` 에 넣고 포토 폴더 백업 → 원본 화질·용량 미증가. 공간 확보가 최근 항목도 지우는지, 지울 때 MediaStore 에서 사라지는지.
- 1단계: §2~11.
- 2단계: 접근성 자동(공간 확보 실행·백업 완료 읽기·포토 깨우기), 플러그 제품(Tapo).
- 3단계: 주력 폰 원본 삭제(`PHOTOS_CONFIRMED` 이후만, 시스템 삭제 확인 창), 기존 사진 보내기.

## 13. 확인 필요
1. 포토 공간 확보가 최근 30일 항목을 남기는지, MediaStore 를 거쳐 지우는지(0단계).
2. OAuth '테스트' 7일 만료가 AuthorizationClient 에도 적용되는지, drive.appdata 로 프로덕션 게시에 검수가 필요한지.
3. Android 17 로컬 네트워크 권한 강제 여부(실기).
4. appDataFolder 가 두 기기(같은 클라이언트·같은 계정)에서 공유되는지(실기, 첫 실험).
5. 삼성 One UI 백그라운드 제한 → 배터리 최적화 제외 안내.
6. 구글 포토 Android 10 지원 종료 시점(미상).

## 14. 구현 중 결정 (메인, 2026-11-12)
- `confirmVanished{sha}` 원격 명령 채택: 주력 폰 "원인 불명 → 포토 확인됨으로 확정" 버튼용. 사용자가 직접 누를 때만, 확인 창(포토 웹에서 원본 확인 경고) 뒤 전송. 결과 = 해당 sha PHOTOS_CONFIRMED → 드라이브 사본 삭제(§7 삭제 규칙 안에 들어감).
- 짝짓기 키 DataStore 평문 저장 수용: 앱 전용 저장소라 다른 앱은 못 읽음. 루팅 안 한 개인 기기 전제. (위험 수용)
- 드라이브 응답에 sha256Checksum 이 없으면 업로드 쪽 대조 생략 — 픽셀이 내려받을 때 전체 SHA-256 을 다시 확인하므로 무결성은 유지.
- 용량 문턱의 '대기열 크기' = 아직 픽셀이 받지 않은 사본 합계.

## 15. 교차검증 1회 반영 결정 (메인, 2026-11-12)
- 서명 보강(§5 대체): `X-PR-Sig = hex(HMAC(key, METHOD\npath?query\ntime\nbodySha256Hex))`. PUT 조각만 bodySha 자리에 `-`(본문 무결성은 commit 의 전체 해시). 파괴적 명령(`confirmVanished`, `resendVanished`)은 본문에 `commandId`(UUID) 필수, 픽셀이 처리한 commandId 를 7일 보관해 재사용 거절. 드라이브 명령 파일도 같은 commandId 규칙.
- 포토 확인 후보(§8 보강): "공간 확보 열기" 버튼을 누르는 순간 PUBLISHED 이면서 파일·MediaStore 둘 다 존재가 확인된 항목만 후보로 기록. 후보가 1시간 안에 사라지면 PHOTOS_CONFIRMED. 버튼 순간 이미 없거나 확인 실패한 항목은 VANISHED_UNKNOWN.
- 수신 직렬화: 받기 쪽 sha 별 잠금(메모리 Mutex, offset 검사~쓰기~해시~rename~장부 전이 전체). 공간 예약: 진행 중 수신의 남은 바이트 합계를 예약으로 잡아 begin 판정에 포함(남은 − 예약 < 크기 + 4GB → 507).
- 공개 이름: 원래 이름 → `이름_sha8` → `이름_sha8_2`… 존재하지 않는 이름이 나올 때까지, 공개 단계는 전역 잠금으로 직렬화, 기존 파일 교체 금지(대상 존재 시 rename 하지 않음).
- 보내기 직렬화: 프로세스 전역 Mutex 로 전송 엔진 실행은 한 번에 하나. 하루 사용량은 보내기 전에 예약, 끝나면 실제 바이트로 정산.
- 장부 갱신: 읽기·전이·쓰기를 한 트랜잭션, 진행 정보는 해당 열만 갱신.
- VANISHED_UNKNOWN 다시 보내기: 주력 폰이 `resendVanished{sha, commandId}` → 픽셀이 그 sha 를 `RESEND_REQUESTED` 로 두고(사본 표·이력 보존) 다음 begin/드라이브 수신을 허용 → 받으면 PUBLISHED.
- sendNow 소진 시점: 주력 폰이 sendNow 에 `expectShas`(이번 배치 sha 목록)를 붙이고, 픽셀은 그 sha 들이 모두 받기 장부에 오르거나 24시간이 지날 때까지 강제 수신 유지.
- 전송 직전 재검사: 미디어 IS_PENDING·크기·수정 시각이 등록 때와 다르면 대기, 안정되면 새 sha 로 다시 등록(옛 항목은 SKIPPED(원본변경)).
- 드라이브 오류: DriveHttpException 을 파일별로 분류(401→인증 경고, 403/429/5xx→WAITING 재시도, 그 외→FAILED 시도 증가). 한 파일 오류가 나머지를 막지 않음.
- 입력 검증: 모든 수신 경계(LAN·드라이브)에서 sha = 64자리 소문자 hex, 크기 ≥ 0, 저장 경로가 .partial 안인지 정규화 검사.
- 용량 문턱 합계 = 미수신 사본 전체 바이트(중복 sha 포함), 다운로드는 sha 당 1회.
