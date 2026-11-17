# 개발용 스크립트

Windows PowerShell 5.1 기준. 스크립트는 UTF-8 BOM으로 저장한다(BOM이 없으면 한글이 깨진다).
실행 정책 때문에 막히면 `powershell -ExecutionPolicy Bypass -File <스크립트>`로 실행한다.

| 스크립트 | 용도 |
| --- | --- |
| `create-public-worry.ps1` | 게스트 세션을 만들고 곧 공개되는 걱정 1건을 제출한다(테스트 데이터용). |
| `smoke-test.ps1` | 서버 전체 흐름을 한 번에 점검한다. |

## smoke-test.ps1

일반 흐름: CSRF → 게스트 세션 → 걱정 제출(30초 뒤 공개) → 분석 완료 대기 → 공개 대기 → 금고 목록·상세 → 열람 → 요약 수정 → 불안도 기록 → 액션 시작·3단계 체크·완료·평가 → 기록 목록 → 홈 요약·이어하기 → 삭제 → 404 확인.
`-DemoKey`를 주면 새 세션에서 시연 흐름(시연 세션 → 프리셋 → 공개 이동 → 상세 → 24시간 이동 → 회고 후보·응답 → 초기화)도 점검한다.

- 단계마다 `[통과]`/`[실패]`와 걸린 시간을 표시한다. 실패하면 요청, 응답 코드, 오류 `code`를 보여주고 종료 코드 1로 멈춘다.
- 걱정 원문·요약·토큰·쿠키·시연 키 값은 출력하지 않는다. 걱정 문구는 `[스모크 테스트]`로 시작하며, 점검 끝에 삭제한다.
- 공개 대기 때문에 일반 흐름만 약 35초 걸린다.

### 로컬

```powershell
docker compose up -d --wait db
$env:NIGHTVAULT_DEMO_ENABLED = 'true'; $env:NIGHTVAULT_DEMO_KEY = 'local-demo-key'
.\gradlew.bat bootRun          # 같은 창에서 실행해야 위 환경변수가 적용된다

# 다른 창에서
.\scripts\dev\smoke-test.ps1                            # 일반 흐름만
.\scripts\dev\smoke-test.ps1 -DemoKey 'local-demo-key'  # 시연 흐름 포함
```

`NIGHTVAULT_AI_URL`이 비어 있으면 Spring 내부 가짜 분석기가 쓰여 분석이 1초 안에 끝난다.

### 배포 서버

```powershell
.\scripts\dev\smoke-test.ps1 -BaseUrl https://<배포 도메인>
```

- 배포 쿠키는 `Secure`라 반드시 `https`로 호출한다.
- 실제 AI 분석을 타므로 분석이 오래 걸리면 `-AnalysisTimeoutSeconds 120`처럼 늘린다.
- 점검용 게스트와 걱정이 하나 생기고 걱정은 끝에서 삭제된다. 게스트는 일반 보관 규칙에 따라 정리된다.

### 발표 당일 점검

```powershell
$key = Read-Host '시연 키'     # 키를 명령 기록에 남기지 않는다
.\scripts\dev\smoke-test.ps1 -BaseUrl https://<배포 도메인> -DemoKey $key
```

- 발표 30분 전쯤 한 번 돌려 `전체 통과`를 확인한다. 시연 흐름은 자체 시연 게스트를 만들고 마지막에 초기화하므로, 발표용 시크릿 창의 시연 데이터에는 영향이 없다.
- 시연 단계에서 404면 서버의 `NIGHTVAULT_DEMO_ENABLED`/`NIGHTVAULT_DEMO_KEY` 설정과 입력한 키를 확인한다.
- 분석 단계에서 멈추면 AI 서버 상태(`NIGHTVAULT_AI_URL`, Solar 키)를 먼저 확인한다. 발표는 프리셋 데이터로 진행할 수 있다.
