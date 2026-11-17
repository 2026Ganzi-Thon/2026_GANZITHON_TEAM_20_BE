# 프런트 연동·보안 점검 기록

점검일: 2026-11-18. 코드와 격리된 테스트 환경을 확인한 기록이다. 실제 배포 서버·모바일 브라우저의 연동 승인 기록은 아니다.

## 점검 기준과 변경 범위

- 백엔드 develop: `8d7a7391c89934a1812161695ffb578272747f80`.
- 백엔드 배포 브랜치/PR #18: `7883d2fd1b4de4ebb9c06c5ea8076d6f9ed17616` + 로컬 보안 변경.
- 백엔드 위기 분기/PR #14: `034415049ca2d4550289046f9567c847669b042f`.
- 프런트 main: `60c45c389c8588aaa31ac0c1f2c5dadf3f0d0f8b`.
- 프런트 feat/fe1: `8bd2e07179382565ceeba6f33afc47c894110965`.
- 프런트 feat/fe2: `7a108fbb16d73d0b0a11044fbb275239b985ac89`.

프런트 세 브랜치를 읽기 전용으로 비교했다. 백엔드는 작업 브랜치와 최신 develop·#14를 합친 별도 검증 사본을 사용했다. GitHub의 파일·브랜치·PR은 변경하지 않았다. 아래 프런트 수정 제안은 아직 해당 프런트 브랜치에 적용하지 않았다.

## 이번에 수정하고 검증한 백엔드 문제

기존 동작에서 잘못된 경로·메서드·Content-Type·Accept 요청을 보내면 공통 `{ code, message }` 형식이 깨졌다. 프런트가 `response.data.code`로 처리하면 정상적인 오류 분기에 진입하지 못할 수 있었다. 신규 테스트를 먼저 실행해 네 경우의 실패를 재현했다.

- `api/ApiError.java`: `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` 추가.
- `api/ApiExceptionHandler.java`: 404·405·406·415를 공통 JSON 오류로 변환. HTTP 상태, 405의 `Allow`, `Cache-Control: no-store` 유지. 406도 JSON 본문으로 오류를 전달한다.
- `FrontendHttpContractTest.java`: 위 네 경우와 게스트 발급 후 이전 CSRF 거부·새 토큰 성공까지 다섯 건 추가.

인증되지 않은 요청에는 기존 보안 필터의 401/403이 먼저 적용될 수 있다. 이 수정은 모든 요청을 라우팅 오류로 바꾸거나 프록시의 HTML 오류까지 JSON으로 바꾸는 기능이 아니다.

## 프런트 병합·시연 전에 해결할 항목

### 1. 실제 배포할 프런트 브랜치와 라우트 통합

**확인:** main과 feat/fe2의 `src/App.jsx`에는 걱정 입력 임시 화면이 남아 있고, 최신 feat/fe1의 입력·확인·분석 흐름이 그대로 연결되어 있지 않다. FE2의 금고 화면만 배포하면 입력부터 분석까지 이어지지 않을 수 있다.

**조치:** 프런트 담당자가 실제 FE1·FE2 라우트를 통합한다. `입력 → 확인 → 공개 시각 → 분석 → 봉인 → 금고 → 공개 상세`를 통과한 브랜치를 배포한다. `/worry-confirm`, `/worry-analysis`를 주소로 직접 열거나 새로고침했을 때도 SPA 진입 처리를 확인한다. `/api/**` 오류를 SPA의 `index.html`로 응답하지 않는다.

근거: [FE2 App.jsx](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/7a108fbb16d73d0b0a11044fbb275239b985ac89/src/App.jsx), [FE1 App.jsx](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/8bd2e07179382565ceeba6f33afc47c894110965/src/App.jsx).

### 2. S-16 실제 안내와 실패 재시도 연결

**확인:** FE1 `WorryAnalysis.jsx`는 `SUPPORT_GUIDANCE` 상태를 구분하지만 안내 화면은 준비 중 문구다. `FAILED`의 재시도는 API 호출 대신 홈으로 이동한다. `src/api/worry.js`에는 retry 호출이 없다. 백엔드에 위기 분기가 있어도 사용자에게 도움 연결을 완성한 상태는 아니다.

**조치:** 기획에서 확인한 안내 문구·도움 연결을 S-16에 넣는다. `POST /worry-requests/{requestId}/retry`를 연결하고 요청 본문과 UUID 헤더를 [API 명세](api/openapi.yaml)에 맞춘다. 같은 전송의 재시도는 같은 멱등 키, 새 재시도 동작은 새 키로 구분한다. 상태 조회에는 해당 요청의 `X-Analysis-Context`를 유지한다. 컨텍스트가 없거나 만료되어 404가 나면 일반 봉인 성공으로 바꾸지 않는다.

30초 경과 자체는 서버의 최종 실패가 아니다. 현재 FE1이 `PENDING`을 유지하는 처리는 보존하고, 화면 이탈·새로고침 뒤 요청 상태를 다시 찾는 경로를 마련한다. 원문이 들어 있는 Router `location.state.retryPayload`도 브라우저 기록에 남을 수 있으므로 완료·S-16·전체 삭제 후 제거한다. 원문을 localStorage로 옮겨 문제를 해결하지 않는다.

**완료 기준:** 합성 일반 입력은 봉인, 합성 위험 입력은 S-16, 최종 실패는 재시도 API, 지연·새로고침은 중복 제출 없이 기존 요청 조회로 복귀한다.

근거: [FE1 WorryAnalysis.jsx](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/8bd2e07179382565ceeba6f33afc47c894110965/src/pages/fe1/WorryAnalysis.jsx), [FE1 worry.js](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/8bd2e07179382565ceeba6f33afc47c894110965/src/api/worry.js).

### 3. 요약 수정 여부를 명시적 필드로 판단

**확인:** FE2 `springContract.js`의 `normalizeWorry`는 `w.summary !== analysis.summary`로 수정 여부를 계산한다. 최신 백엔드는 원본 AI 요약을 공개 상세의 `analysis`에서 제외하고 `summaryEdited`를 직접 제공한다. 따라서 수정하지 않은 상세도 수정했다고 표시될 수 있다.

**조치:** `summary_edited: Boolean(w.summaryEdited)`를 사용한다. 목록·기록·상세 어댑터에 같은 기준을 적용하고, 사라진 `analysis.summary`를 복원하도록 백엔드를 바꾸지 않는다. 원본 요약 노출 제거는 유지한다.

**완료 기준:** 미수정·수정·원래 문구와 같은 내용으로 저장한 사례를 각각 저장 후 재조회·새로고침하여 서버의 플래그대로 표시한다.

근거: [FE2 springContract.js](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/7a108fbb16d73d0b0a11044fbb275239b985ac89/src/features/fe2/springContract.js), [최신 VaultService.java](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_BE/blob/8d7a7391c89934a1812161695ffb578272747f80/src/main/java/com/nightvault/backend/worry/VaultService.java).

### 4. 삭제 202와 응답 유실 뒤 완료 확인

**확인:** FE2 `Deletion.jsx`는 202 응답에 `deletion_job_id`가 있을 것으로 기대한다. 실제 서버는 `{ status: "DELETING" }`을 반환하고 작업 조회 API는 없다. ID가 없으면 `accepted && !jobId`로 버튼이 비활성화되고 sessionStorage에 대기 상태가 남는다. 최신 백엔드는 동기 정리를 먼저 시도하므로 보통 204지만, 202·응답 유실 경로는 여전히 처리해야 한다.

**조치:** 기존 서버의 동일 소유자·동일 걱정 ID DELETE 재확인과 완료 영수증을 사용할지 FE·BE가 합의한다. 사용한다면 해당 ID에만 횟수·간격을 제한해 확인하고 204에서 종료한다. 새 작업 ID나 작업 조회 경로를 프런트가 임의 생성하지 않는다. 404·목록에서 사라짐·타임아웃을 완료로 간주하지 않는다. 전체 삭제 후에는 기존 활동·삭제 메타데이터와 화면 캐시도 비운다.

**완료 기준:** 즉시 204, 202 뒤 204, 응답 유실, 404 거부, 재진입을 각각 확인한다. 처리 중에는 중복 클릭을 막되 결과 재확인 경로는 제공한다.

근거: [FE2 Deletion.jsx](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/7a108fbb16d73d0b0a11044fbb275239b985ac89/src/features/fe2/Deletion.jsx), [최신 VaultDeletion.java](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_BE/blob/8d7a7391c89934a1812161695ffb578272747f80/src/main/java/com/nightvault/backend/deletion/VaultDeletion.java).

### 5. CSRF 저장소와 게스트 전환 처리 통일

**확인:** FE1 `src/api/client.js`와 FE2 `src/features/fe2/api.js`가 서로 다른 CSRF 캐시를 사용한다. FE1은 `CSRF_REJECTED`를 한 번 자동 재시도하지만 FE2는 토큰만 지워 다음 동작을 기다린다. 시연 게스트 발급 후 한쪽 저장소에 이전 토큰이 남아 첫 변경 요청이 403일 수 있다.

**조치:** 공용 클라이언트 또는 공용 CSRF 저장소로 통일한다. `/guest-sessions`, `/demo/session`, `DELETE /vault` 성공 시 이전 토큰을 폐기한다. 게스트 전환 중 요청을 직렬화하고 이전 요청의 화면 반영을 취소한다. 새 토큰을 받은 뒤 변경 요청을 보낸다. 자동 재시도는 명시적인 CSRF 거부에 한 번만 허용하고, 분석·삭제의 응답 유실을 무조건 재전송하는 공통 정책은 만들지 않는다.

**완료 기준:** 게스트 시작 → 첫 걱정 제출, 일반 → 시연 → 시연 제출, 전체 삭제 → 재시작, 두 탭에서 세션 변경을 확인한다. CSRF나 HttpOnly를 끄는 방식으로 해결하지 않는다.

근거: [FE1 client.js](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/8bd2e07179382565ceeba6f33afc47c894110965/src/api/client.js), [FE2 api.js](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/7a108fbb16d73d0b0a11044fbb275239b985ac89/src/features/fe2/api.js). 인증 후 CSRF 갱신 원칙은 [Spring Security 공식 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)를 참고한다.

### 6. 공개 기본 시각·시간대 계약 확인

**확인:** FE1 `WorryTime.jsx`는 현재보다 9시간 뒤를 최소값으로 사용한다. 백엔드의 기본 공개 API는 사용자 시간대에서 다음 기본 공개 시각을 계산하며, 미래·보관 기한 조건을 검사한다. FE1 `settings.js`의 시간대는 `Asia/Seoul`로 고정되어 있다. 9시간 제한을 제품 정책으로 추가할지는 별도 합의가 필요하다.

**조치:** 합의된 정책에 따라 `GET /worry-publication/default?timeZone=...`를 사용하고, 시간대는 브라우저의 IANA 값으로 전달한다. 서버에는 ISO 시각, 화면에는 사용자 지역 시각을 사용한다. 입력 시간이 이미 지나 422가 나면 시각을 다시 고르도록 안내한다. 휴식·회고 판정은 기기 시계보다 서버 응답을 기준으로 한다.

근거: [FE1 WorryTime.jsx](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/8bd2e07179382565ceeba6f33afc47c894110965/src/pages/fe1/WorryTime.jsx), [FE1 settings.js](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_20_FE/blob/8bd2e07179382565ceeba6f33afc47c894110965/src/api/settings.js).

## 배포 주소가 정해지면 확인할 보안·기능 항목

### HTTPS·쿠키·CORS·프록시

현재 권장 구성은 프런트 `/`와 백엔드 `/api/`를 같은 HTTPS 출처로 제공하는 것이다. 출처는 스킴·호스트·포트까지 포함한다. 다른 서브도메인은 같은 사이트일 수 있어도 다른 출처이므로 CORS와 credentials를 따로 확인해야 한다. 완전히 다른 사이트는 SameSite 및 브라우저 쿠키 제한도 추가된다. 허용 출처를 와일드카드로 열거나 운영 Secure 쿠키를 해제하지 않는다. [MDN CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS), [MDN Set-Cookie](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie).

프록시는 신뢰할 수 있는 HTTPS 종단이며 백엔드 직접 접근을 제한해야 한다. 외부가 임의로 보낸 forwarded 헤더를 그대로 신뢰하지 않는다. API 응답·원문·게스트별 화면을 CDN/서비스워커의 공용 캐시에 넣지 않는다. 실제 호스트에서 Safari·Chrome 모바일의 쿠키 저장·새로고침·절전 복귀를 확인한다. 앱/iframe 내 브라우저를 지원한다면 그 경로도 별도 검증한다.

### 공개 API의 호출량·AI 비용 제한

Python의 내부 토큰과 사설 바인딩은 직접 Solar 호출을 막는 경계다. 하지만 일반 사용자는 Spring에서 게스트를 발급받고 새 멱등 키로 분석을 반복 요청할 수 있다. 점검한 애플리케이션 코드에는 IP별·게스트별 분석 쿼터나 공개 세션 발급 속도 제한이 없다. 멱등 키와 작업 재시도 횟수 제한은 새로운 요청의 남용 제한을 대체하지 않는다. 외부 프록시의 제한 설정은 아직 확인되지 않았다.

공개 배포 전 운영 담당과 세션 발급·분석 제출·분석 재시도 경로의 속도/동시 처리/대기열 상한, JSON 본문 크기 제한, 공급자 비용 한도·알림을 정해야 한다. IP만으로 식별하면 학교·행사장 공용망의 정상 사용자를 막을 수 있으므로 게스트와 IP를 함께 고려한다. 429를 도입한다면 재시도 안내 계약도 프런트에 공유한다. 이번 변경에서는 새 429 계약이나 임의의 사용량 정책을 추가하지 않았다.

### 키·시연 권한·입력·로그

Solar 키, 데이터 암호화 키, 내부 AI 토큰을 `VITE_*`, JS 번들, 요청 URL, 브라우저 저장소에 넣지 않는다. FE2 시연 패널은 키를 password 입력·컴포넌트 메모리로 전달한다. 시연 키도 공개 번들에 고정하지 않고 발표용 브라우저에만 입력하며 발표 후 비활성화·교체한다. 키가 있어도 일반 게스트의 시연 조작을 허용하지 않는 기존 서버 검사를 유지한다.

걱정 원문·AI 출력은 텍스트로 렌더링한다. HTML 삽입·외부 링크 자동 생성을 새로 추가하면 별도 검증한다. Axios 오류 객체 전체는 원문·헤더를 포함할 수 있어 콘솔·오류 수집기로 보내지 말고 상태·공개 오류 코드만 수집한다. 프런트 모의 API는 개발 환경 조건을 유지하고, 발표 서버에서 실제 데이터를 모의 성공으로 대체하지 않는다.

### 활동 계약과 푸시 알림

FE2 활동 시작은 `Idempotency-Key`와 `replaceOpen: false`를 함께 보내므로 현재 서버에서는 새 활동 응답을 받는다. **이 호출 자체는 호환 응답 오류가 아니다.** 다만 키가 있고 `replaceOpen`이 빠지면 구형 응답으로 분기하므로 필드를 임의 제거하지 않는다. 다른 탭에서 활동이 바뀌어 409가 나면 재조회·전환 확인을 거치며 타인의 활동 ID를 추측해 조작하지 않는다.

점검한 프런트 소스에서는 실제 PushManager 구독·서비스워커 등록 흐름을 찾지 못했다. 설정의 알림 동의 저장만으로 웹 푸시 수신이 완성되지는 않는다. 알림을 발표 범위에 포함한다면 HTTPS·권한 요청·공개 VAPID 키·구독 API 등록·실제 모바일 수신을 연결한다. 백엔드의 WebPush endpoint 허용 목록과 리다이렉트 차단은 유지한다. [Push API 공식 설명](https://developer.mozilla.org/en-US/docs/Web/API/Push_API).

## 검증 증거와 범위

신규 회귀 테스트 5건을 포함해 최신 develop·배포 변경·#14를 합친 검증 사본에서 JUnit 168건 통과, 실패·건너뜀 0건이다. 아래는 실제 JUnit 출력의 발췌다.

배포 작업 브랜치도 다른 브랜치의 파일이 섞이지 않은 새 검증 사본으로 분리해 JUnit 108건 통과, 실패·건너뜀 0건을 확인했다. 작업 브랜치 `bootJar`는 오프라인 캐시를 사용한 Gradle 실행에서 성공했다. 두 테스트 묶음에는 같은 테스트가 포함되므로 합산해 고유 테스트 수로 보고하지 않는다.

```text
Test run finished after 38172 ms
[       168 tests found           ]
[         0 tests skipped         ]
[       168 tests started         ]
[       168 tests successful      ]
[         0 tests failed          ]
```

위 시간은 전체 테스트 묶음 실행 시간이며 실제 AI 처리시간이나 서비스 응답시간 측정치가 아니다. 테스트는 격리된 PostgreSQL 17과 보안 필터를 켠 MockMvc를 사용했다. 검증 사본에서 Testcontainers 연결부만 임시 DB로 대체하고 캐시된 의존성으로 javac/JUnit을 직접 실행했다. 따라서 Docker 기반 원본 `./gradlew test` 성공으로 표기하지 않는다.

로컬 증거 위치(배포 파일이나 공개 저장소 업로드 대상 아님):

- 수정 전 네 HTTP 계약 실패: `work/nightvault-deployment-review/frontend-protocol-baseline.log`.
- 수정 후 168건 전체 로그: `work/nightvault-deployment-review/frontend-integration-junit.log`.
- 작업 브랜치 108건 전체 로그: `work/nightvault-deployment-review/frontend-branch-junit.log`.
- 실행 도구: `work/nightvault-crisis-rerun/spring_tests.py --junit`, `CrisisJUnit.java`.
- 원본 회귀 테스트: [FrontendHttpContractTest.java](../src/test/java/com/nightvault/backend/api/FrontendHttpContractTest.java).

원본 저장소에서는 Docker 실행 후 Git Bash에서 다음과 같이 재현한다.

```bash
bash ./gradlew test --tests 'com.nightvault.backend.api.FrontendHttpContractTest'
bash ./gradlew test
bash ./gradlew bootJar
```

앞선 배포 보안 테스트·실행 JAR 검증 9개 시나리오는 [배포 점검 기록](deployment-review-record.md)에 기록했다. 이번 테스트가 모바일 E2E, 실제 Solar 반복 평가, 의존성 취약점 전수 검사, 실제 배포 방화벽 검증을 대신하지 않는다.

## 연동 완료 체크리스트

- [x] HTTP 계약 오류 404·405·406·415 공통 JSON 및 no-store 검증.
- [x] 게스트 발급 후 이전 CSRF 거부·새 CSRF 성공 검증.
- [x] 최신 BE2·#14·배포 변경을 합친 백엔드 회귀 168건 통과.
- [ ] 프런트 FE1·FE2 라우트 통합 후 실제 API로 입력부터 금고까지 연결.
- [ ] S-16의 확정 안내·도움 연결 및 실패 재시도 API 연결.
- [ ] `summaryEdited` 사용 및 원본 AI 요약 비노출 유지.
- [ ] 삭제 202·응답 유실·재진입 완료 확인 계약 구현.
- [ ] CSRF 저장소 통합·게스트 전환 시 요청/캐시/브라우저 기록 정리.
- [ ] 공개 기본 시각·9시간 정책·시간대 확정.
- [ ] 실제 HTTPS 출처·쿠키·프록시·모바일 브라우저 확인.
- [ ] 공개 API 호출량·본문·대기열·AI 비용 상한 적용 및 정상 시연 영향 확인.
- [ ] 발표 범위에 포함하면 실제 웹 푸시 구독·모바일 수신 검증.
