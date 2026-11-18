# PR #14·#18 리뷰 보완 결과

2026-11-18 로컬 작업 기록이다. GitHub는 조회만 했으며 원격 커밋·PR·리뷰 상태는 변경하지 않았다. 로컬 구현·검증과 실제 배포·모바일 연동 완료를 구분한다.

리뷰 원문: [#14 최초 리뷰](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_BE/pull/14#pullrequestreview-5919938511), [#14 재리뷰](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_BE/pull/14#pullrequestreview-5938372941), [#18 리뷰](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_BE/pull/18#pullrequestreview-5941400508).

## #14 반영 내역

- B1의 현재 위험·원인 설명 처리와 B2의 Solar 문맥 분류를 유지했다.
- 기존 세 후보 외에 뛰어내림·팔의 상처 표현·없어지고 싶은 표현·다 끝내고 싶은 표현·죽을래를 분류 후보로 추가했다. 이는 임상 판정이 아닌 앱 분기 후보이며 해당 단어만으로 모두 S-16을 확정하지 않는다.
- `아닌데`, `뜻이 아니라`, ASCII 작은따옴표, 엄마·아빠·걔·쟤, 문장 끝 `대`를 맥락 판정에 보완했다. 다른 절의 명확한 현재 신호를 무력화하지 않는지 회귀 테스트했다.
- 모델 요청의 decision 스키마는 `GUIDANCE`·`GENERATE` 두 값이다. 이전 형식의 정확한 `{"decision":"NEEDS_CONTEXT"}`만 S-16으로 복구한다. 추가 필드·알 수 없는 값·잘못된 JSON은 정상 생성으로 통과시키지 않는다.
- 분류 중 시간 초과·429·일시 장애는 `PROVIDER_TIMEOUT`·`PROVIDER_RATE_LIMITED`·`PROVIDER_UNAVAILABLE`를 보존해 Spring의 기존 최대 3회 처리와 연결한다.
- 알 수 없는 예외 문자열은 고정 오류 코드로 바꾼다. 분류 실패 뒤 일반 생성을 호출하지 않는다.
- 첫 분류/생성의 제한과 재시도, 전체 예산 공유를 유지했다. 타이밍 테스트는 짧은 sleep 비교 대신 실제 공유 deadline을 생성 진입 시 만료시키도록 바꿨다. 해당 테스트를 20회 반복해 모두 통과했다.
- mock·Spring 내부 가짜 분석기는 실제 위기 판정을 하지 않는다는 점과 프롬프트 인젝션·누락·오탐 한계를 문서화했다. 명확한 규칙 신호가 사용자 생성 지시에 덮이지 않는 사례를 테스트했지만 모든 우회를 차단한다는 의미는 아니다.

#14 수정 파일:

```text
AI/crisis.py
AI/mock_api.py
AI/solar.py
AI/tests/test_crisis_routing.py
AI/AI_README.md
docs/spec/crisis-routing-evidence.md
CHECKLIST.md
```

## #18 반영 내역

앞선 Python 토큰 필수화·인증 우회 차단·prod dotenv 미사용과 로컬 개발 호환을 유지했다.

- `ProductionEnvironmentValidator`를 ConfigData 직후 실행한다. DB URL·사용자·비밀번호 누락을 데이터소스 초기화 전에 설정 이름으로 안내한다. 실제 값은 출력하지 않는다. 시연 활성인데 키가 없으면 운영 기동을 거부한다.
- 운영 기본 바인딩은 loopback이다. native forwarded 헤더 처리를 켜고 기본 신뢰 프록시는 IPv4·IPv6 loopback으로 제한한다. 다른 호스트는 실제 프록시 주소를 명시한다. [Spring Boot 근거](https://docs.spring.io/spring-boot/how-to/webserver.html).
- 운영 Flyway 기본값은 `out-of-order=false`다. 기존 병합 DB는 백업·적용 이력 대조 후 검토한 배포에만 `NIGHTVAULT_FLYWAY_OUT_OF_ORDER=true`를 명시하는 절차를 추가했다. 적용된 SQL·이력은 변경하지 않았다.
- `LocalAnalysisWorker`를 `AnalysisWorker`로 바꿨다. local/prod 등록, 시연 분기, 순차 처리와 기존 선점·복구는 유지했다. 처리량 증설은 이번 범위에 포함하지 않았다.
- 배포 문서에 JDK 21·DB 생성·키 생성/백업·내부 AI·HTTPS/SPA 프록시·신뢰 헤더·Web Push 설정과 미설정 시 비활성·systemd/재시작/로그·스모크 테스트·프런트 가이드 링크를 정리했다.

이번 단계의 주요 수정/추가 파일:

```text
src/main/java/com/nightvault/backend/analysis/AnalysisWorker.java
src/main/java/com/nightvault/backend/analysis/ProductionEnvironmentValidator.java
src/main/resources/META-INF/spring.factories
src/main/resources/application-prod.yaml
src/test/java/com/nightvault/backend/analysis/DemoAnalysisWorkerTest.java
src/test/java/com/nightvault/backend/analysis/ProductionAnalysisSettingsTest.java
src/test/java/com/nightvault/backend/analysis/ProductionEnvironmentValidatorTest.java
src/test/resources/application-test.yaml
docs/deployment.md
docs/review-followup.md
docs/deployment-review-record.md
CHECKLIST.md
```

기존 `LocalAnalysisWorker.java`는 이름 변경에 따라 삭제 상태다. Git에 추가할 때 새 파일과 기존 파일 삭제를 함께 포함한다. 앞선 미커밋 보안·HTTP 계약 보완도 작업 폴더에 남아 있다. `ProductionAnalysisSettings.java`, `PythonAnalysisClient.java`, 게스트 인증·암호화·BE2 SQL 마이그레이션은 이번 단계에서 수정하지 않았다.

## 두 브랜치를 통합할 때

두 PR이 Python 파일을 함께 수정하므로 한쪽 파일 전체를 덮어쓰지 않는다. 별도 사본에서 아래 조합으로 최신 develop `8d7a739`와 두 작업을 결합했다.

- `mock_api.py`: #14의 규칙·문맥 분류·실패 코드 처리 + #18의 필수 Bearer 인증 검사.
- `solar.py`: #14의 분류·생성·제한 시간 처리 + #18의 `AnalysisSettings` 운영 환경·토큰 검사.
- Python 테스트: #14의 합성 Bearer 토큰을 포함한 테스트와 #18 런타임 보안 테스트를 함께 실행.
- Java: 최신 BE2 오류 코드·접근 검사·활동·삭제·회고를 유지하고 워커 이름과 테스트 참조를 함께 변경.

이 조합은 별도 로컬 사본에서 검증했으며 GitHub 머지는 수행하지 않았다.

## 실제 검증 결과

- #14 Python **27건** 통과, 공유 deadline 회귀 **20회 반복** 통과.
- #18 Spring JUnit **114건**, 실패·건너뜀 0.
- 최신 develop·#14·#18 결합 사본 Spring JUnit **174건**, 실패·건너뜀 0.
- 결합 사본 Python **39건** 통과(#14 27건 + 보안 12건).
- 작업 브랜치와 결합 사본 `bootJar` 성공.
- 실행 JAR·격리 DB·테스트 TLS 프록시 보안/기능 **15개 시나리오** 통과.

Java는 격리 PostgreSQL 17에 연결한 검증 사본에서 javac/JUnit을 직접 실행했다. 원본 Testcontainers 설정은 유지했다. 원본 Docker 기반 `./gradlew test` 성공으로 표기하지 않는다. Java 두 묶음에는 중복이 있어 고유 건수로 합산하지 않는다. Python은 모의 제공자를 사용했고 실제 Solar 530회 재평가를 수행한 것은 아니다.

통합 JUnit 로그 발췌:

```text
[       174 tests found           ]
[         0 tests skipped         ]
[       174 tests started         ]
[       174 tests successful      ]
[         0 tests failed          ]
```

실행 JAR 로그 발췌:

```text
PASS early prod validation names missing NIGHTVAULT_DB_URL
PASS early prod validation names missing NIGHTVAULT_DEMO_KEY
PASS prod startup ignores .env fallback for NIGHTVAULT_DATA_KEY
PASS Python prod requires the correct bearer token
PASS untrusted loopback peer cannot spoof forwarded HTTPS
PASS trusted HTTPS proxy enables HSTS
PASS prod HTTPS CSRF, Secure guest cookie and authenticated Python sealing
PASS prod demo submission, seal and time-machine publication
PASS plain HTTP loses the Secure CSRF cookie and returns 403
PASS default Spring local retains .env, guest auth and built-in mock
```

로컬 원본 로그는 `work/nightvault-deployment-review/`의 `review-followup-branch-junit.log`, `review-followup-integration-junit.log`, `review-followup-python.log`, `review-followup-crisis-python.log`, `review-followup-runtime.log`에 있다. 실행 폴더·가상환경·인증서·합성 환경 파일은 Git 업로드 대상이 아니다.

## 실제 배포에서 남는 확인

실제 도메인·인증서·방화벽·프록시 주소와 모바일 S-16·재시도·삭제·쿠키를 확인한다. Web Push 사용 여부, 프런트 CSRF 저장소 통합, 공개 API 호출량·대기열·AI 비용 제한은 [프런트 점검 기록](frontend-integration-review.md)을 따른다. 이 항목은 이번 리뷰 코드 보완과 별도의 배포/프런트 작업이며 완료로 표시하지 않았다.
