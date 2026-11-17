# 배포 PR 보안 리뷰 점검 기록

후속 리뷰 보완의 최신 결과는 [PR #14·#18 보완 기록](review-followup.md)을 따른다. 이후 워커 이름 변경, 운영 설정 선검사, 신뢰 프록시·Flyway 정책을 추가했고 작업 브랜치 114건·통합 사본 174건·Python 39건·실행 JAR 15개 시나리오를 통과했다. 아래의 103/163/168건은 각 이전 단계의 기록이다.

2026년 11월 18일에 배포 PR #18의 `feature/be1-prod-analysis`를 점검했다. 기준 커밋은 `7883d2f`다. 원격 조회에서 `develop`의 `8d7a739`와 BE2 후속 PR #16·#17의 머지를 확인했다.

## 이번에 반영한 항목

- Python은 모의·Solar 모드 모두 내부 토큰이 없으면 시작에 실패한다. 빈 런타임 토큰과 누락·잘못된 Bearer 토큰은 `401`을 반환한다.
- Python의 기본 환경은 `prod`이며 `.env`를 읽지 않는다. 프로세스에 `NIGHTVAULT_ENV=local`을 명시한 로컬 실행에서 파일을 읽는다. Spring의 `prod`와 Python의 `local`이 함께 지정되면 시작에 실패한다.
- Spring의 `.env` import를 `application-local.yaml`로 이동했다. 명시적인 `prod`에서는 파일이 읽히지 않고, 기본 로컬 실행에서는 기존 파일을 사용한다.
- Python 기본 제한을 24초, 배포 프로필의 Spring 분석 제한을 27초로 맞췄다.
- 배포 문서에 JDK 21, PostgreSQL DB·사용자 생성, 데이터 키·내부 토큰 생성과 백업, HTTPS·같은 출처 프록시, 쿠키·CSRF 점검을 추가했다.
- 같은 호스트에서 Python을 `127.0.0.1:8001`에 바인딩하는 실행 절차를 문서화했다. 별도 컨테이너 배치는 사설 네트워크와 외부 포트 비공개를 사용한다.

수정한 파일은 다음과 같다.

- `.env.example`
- `AI/AI_README.md`
- `AI/mock_api.py`
- `AI/solar.py`
- `src/main/resources/application.yaml`
- `src/main/resources/application-local.yaml`
- `src/main/resources/application-prod.yaml`
- `docs/deployment.md`

추가한 파일은 `AI/tests/test_runtime_security.py`와 이 점검 기록이다. #14 작업 폴더의 `AI/tests/test_crisis_routing.py`에는 테스트용 Bearer 토큰·헤더를 설정했다. 기본 제한 시간 테스트에도 합성 내부 토큰을 주입해 새 인증 정책과 함께 실행할 수 있게 했다.

## 이미 적용되어 수정하지 않은 파일

배포 PR의 아래 구현은 요청한 동작이 이미 있어 유지했다.

- `src/main/java/com/nightvault/backend/analysis/ProductionAnalysisSettings.java` — 필수 데이터 키·Python URL·토큰, Secure 쿠키, local·prod 혼용 검사.
- `src/main/java/com/nightvault/backend/analysis/LocalAnalysisWorker.java` — local·prod 프로필에서 분석 워커와 시연 예시 분기 실행.
- `src/main/java/com/nightvault/backend/analysis/PythonAnalysisClient.java` — local·prod에서 내부 분석 API 사용.
- `src/main/java/com/nightvault/backend/guest/GuestSessionCookie.java` — HttpOnly·Secure·SameSite 쿠키 생성.
- `src/main/java/com/nightvault/backend/guest/GuestSecurityConfiguration.java` — 게스트 인증과 CSRF 검사.
- `src/test/java/com/nightvault/backend/analysis/ProductionAnalysisSettingsTest.java` — 운영 필수값·쿠키·프로필 회귀 검사 5건.
- `build.gradle` — Java 21 도구 체인과 Gradle 의존성.

#14의 다음 파일도 해당 작업 폴더에서 유지했다. 기존 위기 판정과 생성·재시도 로직을 확인하고, 두 PR을 합친 검증 사본에서 병합과 테스트를 수행했다.

- `AI/crisis.py`
- `AI/solar.py`
- `AI/mock_api.py`
- `AI/AI_README.md`
- `.env.example`
- `docs/spec/crisis-routing-evidence.md`

## 이미 머지된 BE2 파일

BE2 PR #16은 `e42678b`, #17은 `8d7a739`에서 `develop`에 머지돼 있었다. 아래 파일은 팀원 구현을 유지하고 통합 검증에서 사용했다.

PR #16의 파일 목록이다.

```text
docs/api/frontend-guide.md
docs/api/openapi.yaml
docs/api/stage5-6.md
src/main/java/com/nightvault/backend/activity/ActivityStore.java
src/main/java/com/nightvault/backend/api/ApiError.java
src/main/java/com/nightvault/backend/deletion/VaultDeletion.java
src/main/java/com/nightvault/backend/reflection/ReflectionController.java
src/main/java/com/nightvault/backend/reflection/Reflections.java
src/main/java/com/nightvault/backend/worry/VaultService.java
src/main/java/com/nightvault/backend/worry/WorryAccess.java
src/main/java/com/nightvault/backend/worry/WorryActivityHistory.java
src/main/java/com/nightvault/backend/worry/WorryEdits.java
src/main/java/com/nightvault/backend/worry/WorryRecords.java
src/main/java/com/nightvault/backend/worry/WorrySummaries.java
src/main/resources/db/migration/V202611171000__add_summary_edited_at.sql
src/main/resources/db/migration/V202611171010__add_reflection_skip_time_zone.sql
src/test/java/com/nightvault/backend/deletion/VaultDeletionApiTest.java
src/test/java/com/nightvault/backend/reflection/ReflectionApiTest.java
src/test/java/com/nightvault/backend/support/ApiTestSupport.java
src/test/java/com/nightvault/backend/worry/PastExperienceTest.java
src/test/java/com/nightvault/backend/worry/RecordApiTest.java
src/test/java/com/nightvault/backend/worry/VaultApiTest.java
src/test/java/com/nightvault/backend/worry/WorrySummaryEditTest.java
```

PR #17의 파일 목록이다.

```text
scripts/dev/README.md
scripts/dev/smoke-test.ps1
```

## 검증 방법과 결과

- 배포 브랜치의 Python 보안 테스트 12건 통과.
- 배포 브랜치의 Spring JUnit 103건 통과.
- 최신 `develop`과 배포 변경을 합친 사본의 Spring JUnit 159건 통과.
- #14까지 병합한 최종 검증 사본의 Spring JUnit 163건 통과. 실패·건너뜀은 0건이다.
- #14까지 병합한 사본에서 Python 테스트 34건 통과. 새 보안 테스트 12건과 #14 회귀 22건을 포함한다.
- 배포 브랜치와 최신 `develop` 검증 사본에서 `bootJar` 성공.

Spring 테스트는 격리된 PostgreSQL 17과 테스트 사본을 사용해 JUnit을 직접 실행했다. 검증 사본의 Testcontainers 접속 부분을 임시 PostgreSQL에 맞췄다. 저장소의 Testcontainers 설정은 기존 구성을 사용한다. 이번 보안 검증은 모의 AI와 합성 입력을 사용했다.

실행 JAR에서는 합성 키·토큰과 개발용 `.env`를 둔 작업 디렉터리를 사용했다. 운영 필수 키가 없을 때 `.env` 값으로 시작하는지, HTTPS 프록시에서 인증·분석이 이어지는지 점검했다. TLS 검증에는 테스트 인증서를 명시적으로 신뢰하는 클라이언트를 사용했다.

```text
PASS prod startup ignores .env fallback for NIGHTVAULT_DATA_KEY
PASS prod startup ignores .env fallback for NIGHTVAULT_AI_TOKEN
PASS Python prod startup rejects .env token fallback
PASS explicit Python local loads .env
PASS Python prod requires the correct bearer token
PASS prod HTTPS CSRF, Secure guest cookie and authenticated Python sealing
PASS prod demo submission, seal and time-machine publication
PASS plain HTTP loses the Secure CSRF cookie and returns 403
PASS default Spring local retains .env, guest auth and built-in mock
```

최종 검증 사본의 JUnit 출력이다.

```text
[       163 tests found           ]
[         0 tests skipped         ]
[       163 tests started         ]
[       163 tests successful      ]
[         0 tests failed          ]
```

## 업로드 후 연동할 항목

배포 문서는 같은 호스트와 같은 HTTPS 출처를 기준으로 작성했다. 실제 배포 플랫폼·발표 도메인이 확정되면 해당 주소·TLS·사설망 설정을 적용한다. 실제 프런트 화면의 S-16 전환과 모바일 브라우저 점검은 팀 배포 환경에서 수행한다. 배포에서 `.env` 대신 플랫폼 비밀 설정을 사용하며 Python의 `NIGHTVAULT_ENV=prod`, 내부 토큰, 두 서버의 24초·27초 설정을 확인한다.

## 후속 프런트 연동 점검 (2026-11-18)

위 기록 이후 프런트 세 브랜치를 읽기 전용으로 비교했다. HTTP 계약 오류 네 경우를 재현하고 `ApiError.java`, `ApiExceptionHandler.java`에 404·405·406·415 JSON 처리를 추가했다. 기존 BE2 오류 코드는 유지하는 추가 변경이다. 게스트 발급 후 CSRF 재조회까지 포함한 새 테스트 다섯 건과 통합 회귀 168건이 모두 통과했다. 수정 후 작업 브랜치의 `bootJar`도 성공했다. 이전 절의 163건은 후속 다섯 건을 추가하기 전 결과다.

프런트의 미완료 연결, 삭제 202, 요약 수정 플래그, CSRF 저장소, HTTPS 출처, 공개 API 남용 제한 및 검증 한계는 [프런트 연동·보안 점검 기록](frontend-integration-review.md)을 따른다. 프런트 소스와 GitHub 원격은 수정하지 않았다.
