# 간지톤 · NightVault 개발 계획

NightVault는 밤에 떠오른 걱정을 금고에 맡겨 두고, 정해진 공개 시각 이후 AI 요약을 확인해 파쇄·10분 액션·3분 휴식·공감 메시지 중 원하는 도움을 고르는 모바일 웹 서비스다.

| 영역 | 담당 | 담당 범위 | 역할별 계획 |
| --- | --- | --- | --- |
| BE1 | BE1·AI 담당자 | 게스트 인증, 걱정 접수와 비동기 봉인, 공개 제어, 알림, 위기 안내 연동 | [BE1 개발 계획](docs/roles/BE1/readme.md) |
| AI | BE1·AI 담당자 | 위기 대응 분기, Solar 연동, 콘텐츠 생성·검증, 응답 품질 평가 | [AI 개발 계획](AI/readme.md) |
| BE2 | BE2 담당 팀원 | 활동·평가·회고·기록·과거 경험, 삭제·만료, 시연 | [BE2 개발 계획](docs/roles/BE2/readme.md) |

## 폴더 구성

- `src/`에는 BE1·BE2가 공유하는 Spring 백엔드 코드와 설정을 둔다.
- `AI/`는 Python AI 서비스의 작업 폴더다.
- `docs/`에는 [API 명세](docs/api/openapi.yaml), [ERD](docs/erd/erd.md), 역할별 개발 계획을 모아 둔다.

## 확정한 개발 스택

- **BE1·BE2**는 Java 21, Spring Boot 4.1.2, Spring Security와 PostgreSQL을 사용한다. 빌드는 Gradle Wrapper 9.5.1로 맞추고 공통 의존성은 Spring Boot의 버전 관리를 따른다. 로컬 DB는 PostgreSQL 17을 사용하고 스키마 변경은 Flyway로 관리한다. [Spring Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html) · [Flyway 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html#howto.data-initialization.migration-tool.flyway)
- **AI**는 Python으로 구현한다. Solar Mini 4는 평가할 첫 모델 후보다.

### 제안하는 실행 구조

Java 백엔드 한 개를 두고 BE1·BE2가 기능별 패키지를 나눠 개발한다. 인증·소유권 검사, PostgreSQL 스키마와 마이그레이션은 공유한다. Java 소스는 루트의 `src/main/java`에 두고 `BE1`·`BE2`의 역할별 계획을 기준으로 기능 패키지를 나눈다.

모바일 브라우저는 Spring API만 호출한다. Spring의 작업 처리기가 비공개 HTTP/JSON으로 Python AI에 분석을 요청한다. Python은 위기 분기와 생성·검증 결과를 반환하고, Spring은 요청 상태·재시도·저장·공개·삭제를 관리한다. PostgreSQL에는 Spring에서 접근하고, Solar API 키는 Python 서버 설정에 둔다. 이 실행 구조와 내부 통신 방식은 구현 제안이다.

## 로컬 실행

Windows PowerShell에서 프로젝트 루트로 이동하고 Docker Desktop을 실행한다. Gradle Wrapper가 고정된 Gradle 버전을 사용하므로 팀원은 JDK 21과 Docker Compose를 준비하면 된다.

```powershell
if (!(Test-Path -LiteralPath '.env')) { Copy-Item -LiteralPath '.env.example' -Destination '.env' }
if (!(Select-String -LiteralPath '.env' -Pattern '^NIGHTVAULT_DATA_KEY=' -Quiet)) {
    $keyBytes = [byte[]]::new(32)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($keyBytes) } finally { $random.Dispose() }
    Add-Content -LiteralPath '.env' -Value ('NIGHTVAULT_DATA_KEY=' + [Convert]::ToBase64String($keyBytes))
}
docker compose up -d --wait db
docker compose exec db psql -U nightvault -d nightvault -c 'select current_database(), current_user'
.\gradlew.bat build
.\gradlew.bat bootRun
```

- **PostgreSQL**은 걱정·분석 요청 등 데이터를 저장하는 서버다. Docker Compose가 개발 DB를 만들고, Spring이 JDBC로 `127.0.0.1:5432/nightvault`에 접속한다. `psql`은 DB에 직접 접속해 SQL을 실행하는 도구다. 위 명령은 접속된 DB와 사용자를 확인한다.
- **Flyway**는 `src/main/resources/db/migration`의 버전이 붙은 SQL을 순서대로 적용하고 DB에 적용 이력을 남긴다. 첫 마이그레이션은 게스트·세션·설정 테이블을 만든다. [V1 마이그레이션](src/main/resources/db/migration/V1__create_guest_tables.sql)
- **개발 DB 설정**은 로컬 전용이다. 프로젝트 루트의 `.env`에서 `SPRING_PROFILES_ACTIVE`와 DB 설정을 관리한다. 포트가 겹치면 `POSTGRES_PORT=5433`으로 바꾼 뒤 Compose와 Spring을 다시 실행한다. 로컬 설정은 따옴표 없이 `KEY=value`로 작성하고, Git에는 `.env.example`만 공유한다. 처음 생성한 DB의 계정·이름은 PostgreSQL 볼륨에 남으므로 기존 볼륨을 재사용할 때는 초기 설정에 맞춘다.
- **DB 중지**는 `docker compose stop db`로 한다. 데이터는 이름 있는 Docker 볼륨에 남는다.

macOS와 Linux에서는 `./gradlew`를 사용한다. Windows에서 최초 Git 커밋을 준비할 때 `git add --chmod=+x -- gradlew`로 이 스크립트의 실행 권한을 함께 기록한다.

## 걱정 접수 로컬 확인

Spring 시작 시 Flyway V2가 분석 요청·재시도·걱정·AI 결과 테이블을 만든다. 원문과 분석 결과의 복호화에는 데이터를 저장할 때의 `NIGHTVAULT_DATA_KEY`를 사용한다.

게스트 세션을 만든 뒤 CSRF 토큰을 다시 조회한다. 걱정 제출에는 `Idempotency-Key`와 `X-Analysis-Context`에 각각 UUID를 보내고, `202` 응답의 `requestId`로 상태를 확인한다. 같은 제출의 재전송에는 기존 제출 키를 사용한다. [공개 API 명세](docs/api/openapi.yaml)

`local` 프로필의 가짜 분석은 일반 입력에 테스트 콘텐츠를 반환한다. 원문을 `[mock:failure]`, `[mock:guidance]`, `[mock:timeout]` 중 하나로 입력하면 각각 최종 실패, 같은 앱 문맥에서만 보이는 위기 안내, 작업 시간 초과를 확인할 수 있다. 시간 초과는 총 3회 시도하고, 위기 안내 조회는 가짜 분석 완료 후 15분 동안 허용한다.

## 핵심 흐름

1. 게스트가 걱정과 불안도, 선택 메모, 공개 일시를 제출한다. BE1은 입력을 검증하고 요청 ID를 반환한다.
2. 서버가 비동기로 위기 대응 여부를 판단한다. 정상 요청은 AI 콘텐츠를 생성·검증한 뒤 저장한다. 위기 대응 요청은 도움 안내로 분기하고 일반 기록과 알림 예약에서 제외한다.
3. 저장 성공으로 봉인이 완료된다. 공개 전에는 작성·공개 일시 등 허용된 메타데이터만 노출한다. 공개는 서버 시각으로 판단한다.
4. 사용자가 공개된 걱정을 열어 요약을 확인·수정하고 도움을 선택한다. BE2가 활동·평가·회고를 기록하며, 파쇄·일반 삭제·만료는 공통 삭제 흐름을 이용한다.

## 먼저 합의할 ERD와 API 초안

1. **BE1·AI 담당자**가 게스트, 분석 요청, 걱정 기록, AI 콘텐츠의 관계와 생명주기를 제안한다. 게스트 인증, 걱정 제출·상태 조회, 잠긴 목록·공개 상세의 공개 API 예시와 Java–Python 결과 계약을 작성한다.
2. **BE2 담당자**가 활동·평가·회고를 걱정에 연결하고, 상세 열람의 기록 위치와 삭제·만료 처리 범위를 함께 확정한다. 합의한 공개 상세 응답을 바탕으로 가짜 데이터에서 BE2 개발을 시작한다.
3. [ERD](docs/erd/erd.md)에는 소유권·유일 제약·공개와 만료 시각·삭제 경계를, [공개 OpenAPI](docs/api/openapi.yaml)에는 인증·요청/응답 예시·오류·잠긴 응답의 허용 필드를 기록한다. [Java–Python 분석 계약](docs/api/ai-contract.yaml)은 별도 명세로 관리한다.

## 통합 구현 순서

| 순서 | AI | BE1 | BE2 | 통합 완료 기준 |
| --- | --- | --- | --- | --- |
| 1. 규격 합의 | 입력·출력과 오류 스키마, 가짜 응답 준비 | 게스트 인증, 요청 ID, 접수·조회 상태 정의 | 걱정·활동·평가 관계와 공통 삭제 경계 정의 | 프런트엔드와 예시 응답을 공유하고, 역할별 작업을 가짜 데이터로 시작할 수 있다. |
| 2. 걱정 맡기기 | Python 가짜 응답으로 정상·위기·실패 계약 확인 | Spring Security 인증 → PostgreSQL 접수 → 작업 처리 → 봉인·상태 조회 | 공개된 예시 기록으로 상세·활동 저장 개발 | 가짜 AI를 연결해 제출과 봉인을 한 번 통과한다. 응답 유실 후에도 중복 생성이 없다. |
| 3. 공개와 도움 | Solar 연결, 한국어 품질·처리 시간 확인 | 공개 시각 검증, 잠긴 목록과 공개 상세, 열람 기록 | 요약 수정, 불안도, 액션·휴식·공감과 선택 평가 | 모바일에서 공개 전 접근이 제한되고, 공개 후 도움 선택부터 기록까지 이어진다. |
| 4. 안전·운영 흐름 | 위기 분기 검증, API 장애와 검증 실패 처리 | 위기 안내 연동, 공개 알림·재알림, 실패 요청 정리 | 파쇄·전체 삭제·만료, 회고·과거 경험, 시연 패널 | 위기 경로는 일반 기록에 남지 않는다. 삭제 후 늦은 결과도 폐기되고 시연은 운영 데이터와 분리된다. |
| 5. 통합 확인 | 합성 데이터로 회귀 점검 | 느린 연결·중복 제출·권한 오류·알림 예외 점검 | 활동 복원·삭제 재시도·시연 초기화 점검 | 아이폰·안드로이드 브라우저에서 정상·지연·이탈·실패를 재현한다. |

## 역할 사이에서 먼저 맞출 규격

| 합의 항목 | 담당 | 기준 |
| --- | --- | --- |
| 분석 요청·결과 | BE1 ↔ AI ↔ 프런트엔드 | 제출·상태 조회·사용자 재시도의 응답 형식, 중복 제출 식별, 재시도 시도 구분, 정상·위기·실패 처리. 15초 지연 안내와 30초 상태 확인은 UI 기준이며, 서버 처리 제한은 별도로 정한다. |
| 공개·상세 | BE1 ↔ BE2 ↔ 프런트엔드 | 서버 시각 기준으로 권한·공개·삭제·만료를 확인한다. 잠긴 응답은 허용된 메타데이터만 사용한다. 최초 실제 상세 열람은 금고 상세와 기록 상세가 공유한다. |
| 콘텐츠·활동 | AI ↔ BE1 ↔ BE2 | 걱정 ID, 생성 콘텐츠와 활동을 연결하는 방식, 도움 유형, 요약 수정본과 AI 원본의 표시 우선순위, 활동 상태와 이력. |
| 삭제·만료 | BE2 ↔ BE1 | 접근 차단 시점, 관련 데이터·알림 정리, 분석 작업 중단·늦은 결과 폐기, 부분 실패 재처리, 전체 삭제 시 인증 제거 조건. |
| 모바일·외부 API | AI ↔ BE1 ↔ 프런트엔드 | 휴대폰은 입력·상태 조회·결과 표시를 담당한다. 모델 호출과 API 키는 서버에서 관리하고, 실제 데이터를 보내기 전 제공자 처리 조건과 로그 범위를 확인한다. |

## 구현 전에 확정할 항목

- BE1·BE2의 기능 패키지 경계, 운영 PostgreSQL 버전, Python 버전·HTTP 프레임워크, API 경로·오류 형식과 배포 환경.
- PostgreSQL 작업 테이블을 쓰는 초안을 기준으로 작업 선점·복구·예약 실행 방식을 검토한다.
- 별도 위기 대응 정책의 감지 조건·안내 문구·연결 정보, AI 제공자에게 전달할 필드와 데이터 처리 범위.
- 서버 작업 제한 시간·자동 재시도 횟수, 공개·회고에 사용할 사용자 시간대 규격, 검색 관련성 기준.
- 인증·소유권 검사, 민감 데이터 암호화와 로그 제외, 접수 시각 기준 30일 만료 및 삭제·복원 정책의 구현 방식.

기준 문서는 기능명세서, 화면 동작 정책, 수정 반영 기획안이다. 위기 대응 상세와 API·DB 설계는 별도 문서를 확인해 반영한다.
