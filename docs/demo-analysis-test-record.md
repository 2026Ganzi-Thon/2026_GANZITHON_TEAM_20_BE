# 시연 게스트 분석 테스트 기록

2026년 11월 16일에 `feature/be1-demo-analysis`의 작업 트리를 검증했다. 기준 커밋은 `213b5a7`이며, 시연 게스트의 직접 제출과 실패 재시도에 예시 분석을 적용한 변경이 포함되어 있다.

## 검증 환경

- Java 21, Spring Boot 4.1.2, PostgreSQL 17을 사용했다. Gradle로 테스트와 실행 JAR 빌드를 수행했다.
- 테스트는 별도 PostgreSQL 인스턴스의 임시 DB를 사용했다. Docker 엔진 접근 제한으로 테스트 실행용 사본만 임시 DB에 연결했고, 프로젝트의 Testcontainers 설정은 기존 구성을 사용한다.
- HTTP 검증에서는 실제 Spring 서버를 실행하고 Python 내부 분석 엔드포인트는 호출 횟수를 기록하는 테스트 대역에 연결했다. 모든 입력은 합성 데이터였다.

## JUnit 실행 결과

**98건 통과, 실패 0건, 오류 0건, 건너뜀 0건.** 이번에 추가한 `DemoAnalysisWorkerTest` 4건이 전체 결과에 포함된다.

- `demoSubmissionUsesExampleAndPreservesInputAndPublication` — 직접 제출이 성공하고 원문·메모·공개 시각이 보존됐다. 요약·추천 근거·액션 3단계·휴식·공감에 `[시연 예시]`가 붙었고 Python 호출은 0회였다.
- `demoPresetFailureCanBeRetriedInTheSameRequestWithoutPython` — 시연 프리셋과 같은 형태의 실패 요청을 재시도했다. 요청 ID와 최초 접수 시각을 유지하며 예시 결과를 저장했고 Python 호출은 0회였다.
- `regularGuestKeepsItsPythonResult` — 일반 게스트는 Python을 호출하고 반환받은 분석 결과를 저장했다.
- `regularGuestKeepsItsPythonFailure` — 일반 게스트의 Python 분석 실패는 `FAILED`로 처리됐고 걱정이 생성되지 않았다.

Gradle 콘솔과 JUnit XML에서 발췌했다. Gradle 기록의 32초는 전체 빌드 실행 시간이다.

```text
> Task :test
BUILD SUCCESSFUL in 32s
<testsuite name="com.nightvault.backend.analysis.DemoAnalysisWorkerTest"
  tests="4" skipped="0" failures="0" errors="0">
```

JUnit XML의 전체 집계는 Gradle의 `build/test-results/test/TEST-*.xml`을 기준으로 계산했다. [회귀 테스트 코드](../src/test/java/com/nightvault/backend/analysis/DemoAnalysisWorkerTest.java)

## HTTP 검증 실행 기록

```text
PASS demo API: issue guest, direct submit, seal, unlock, show tagged example; Python calls = 0
PASS demo API: retry preset failure on the same request; Python calls = 0
PASS regular API: original Python route used and result encrypted; Python calls = 1
```

- 시연 세션을 발급한 뒤 직접 걱정을 제출했다. 봉인 직후 상세는 잠겨 있었고, 타임머신으로 공개한 뒤 원문·메모와 `[시연 예시]` 요약을 확인했다.
- 프리셋으로 실패 요청을 만든 뒤 같은 요청 ID에 재시도를 보냈고, 예시 결과로 봉인됐다.
- 일반 게스트의 제출은 Python 테스트 대역으로 한 번 전달됐다. PostgreSQL에 분석 결과의 암호문이 저장됐다.

## 재현 명령

시연 분석 작업 폴더의 Git Bash에서 Docker Desktop 엔진을 실행한 상태로 테스트한다.

```bash
bash ./gradlew test --tests 'com.nightvault.backend.analysis.DemoAnalysisWorkerTest'
bash ./gradlew test
```

API 확인 시 시연 세션 발급 후 CSRF 토큰을 새로 받는다. 직접 제출은 `POST /api/v1/worry-requests`, 실패 재시도는 `POST /api/v1/worry-requests/{requestId}/retry`를 사용한다. 응답의 `Location`을 같은 앱 문맥으로 조회하고 `SUCCEEDED`와 걱정 ID를 확인한다. [시연 세션과 타임머신 API](api/demo-guide.md)
