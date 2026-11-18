# 프런트 연동 가이드

React + Vite + JavaScript + React Router + Axios 기준. 상세 스펙은 [openapi.yaml](openapi.yaml)을 본다.

2026-11-18 실제 프런트 브랜치와 대조한 미완료 항목·검증 결과는 [프런트 연동·보안 점검](../frontend-integration-review.md)을 함께 본다.

## 1. 개발 프록시 (Vite)

```js
// vite.config.js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080' },
    },
  },
});
```

- 브라우저는 `http://localhost:5173/api/...`만 호출한다. 같은 출처라서 **CORS 설정이 필요 없고**, 쿠키도 그대로 오간다.
- `target`은 `localhost`가 아니라 `127.0.0.1`로 쓴다. 로컬 서버는 `127.0.0.1`에만 바인딩되는데(`application-local.yaml`), Node 17+는 `localhost`를 `::1`(IPv6)로 풀 수 있어 프록시가 `ECONNREFUSED`로 실패한다.
- 로컬 프로필은 쿠키 `Secure`가 꺼져 있어 http에서 동작한다.

## 2. Axios 인스턴스

서버가 쓰는 값

| 항목 | 값 |
|---|---|
| CSRF 토큰 조회 | `GET /api/v1/csrf` → `{ "token": "...", "headerName": "X-CSRF-TOKEN" }` |
| CSRF 헤더 | `X-CSRF-TOKEN` (POST·PUT·PATCH·DELETE 모두 필요) |
| 쿠키 | `NIGHTVAULT_SESSION`(게스트 세션), `JSESSIONID`(CSRF 토큰 보관). 둘 다 HttpOnly라 JS에서 읽지 않는다 |

- 게스트 세션이 **새로 발급되면 서버가 CSRF 토큰을 버린다.** `POST /guest-sessions` 다음에는 토큰을 다시 받아야 한다(이전 토큰은 `403 CSRF_REJECTED`).

```js
// src/api/client.js
import axios from 'axios';

export const api = axios.create({ baseURL: '/api/v1', withCredentials: true });

const UNSAFE = ['post', 'put', 'patch', 'delete'];
let csrf = null; // { token, headerName }

async function fetchCsrf() {
  const { data } = await api.get('/csrf');
  csrf = data;
  return data;
}

api.interceptors.request.use(async (config) => {
  if (UNSAFE.includes(config.method)) {
    const { token, headerName } = csrf ?? (await fetchCsrf());
    config.headers[headerName] = token;
  }
  return config;
});

api.interceptors.response.use(
  async (res) => {
    // 게스트 교체·전체 삭제 후 이전 토큰을 버리고, 다음 변경 요청 전에 다시 받는다.
    const { url, method } = res.config;
    if ((method === 'post' && ['/guest-sessions', '/demo/session'].includes(url)) ||
        (method === 'delete' && url === '/vault')) {
      csrf = null;
    }
    return res;
  },
  async (error) => {
    const { config, response } = error;
    if (config && response?.status === 403 && response.data?.code === 'CSRF_REJECTED' && !config._csrfRetried) {
      config._csrfRetried = true;
      await fetchCsrf();
      return api(config); // 1회만 재시도
    }
    return Promise.reject(error);
  },
);

/** 오류 응답에서 코드만 꺼낸다. 네트워크 오류면 null */
export const errorCode = (error) => error.response?.data?.code ?? null;
```

위 코드는 최소 예시다. FE1·FE2가 토큰 저장소를 공유하고, 최초 `/csrf` 동시 호출은 하나의 Promise로 합친다. 게스트 교체 중에는 이전 요청을 취소하고 교체 완료 뒤 새 토큰을 받는다. 자동 재전송은 명시적인 `CSRF_REJECTED`에 한 번만 적용한다. 타임아웃·일반 403·네트워크 오류에는 변경 요청을 자동 재전송하지 않는다.

## 3. 앱 시작 흐름

```js
// src/api/guest.js
import { api, errorCode } from './client';

/** @returns 'READY' | 'NEED_START' | 'INVALID' */
export async function checkGuest() {
  try {
    await api.get('/guest-sessions/current'); // 200이면 세션 만료가 연장된다
    return 'READY';
  } catch (e) {
    if (errorCode(e) === 'GUEST_SESSION_REQUIRED') return 'NEED_START';
    if (errorCode(e) === 'GUEST_SESSION_INVALID') return 'INVALID';
    throw e;
  }
}

export const startGuest = () => api.post('/guest-sessions', { mode: 'START' });
export const restartGuest = () => api.post('/guest-sessions', { mode: 'RESTART' });
```

| `GET /guest-sessions/current` 결과 | 화면 |
|---|---|
| 200 `{ authenticated: true, expiresAt }` | 홈으로 |
| 401 `GUEST_SESSION_REQUIRED` (쿠키 없음) | 온보딩 → "시작하기"에서 `startGuest()` |
| 401 `GUEST_SESSION_INVALID` (쿠키가 있지만 무효·만료) | "이용 정보를 확인할 수 없어요" 안내 → "새로 시작"에서 `restartGuest()` |

- 무효 쿠키로 `START`를 보내면 401 `GUEST_SESSION_INVALID`다. 이때는 `RESTART`를 쓴다.
- 다른 API에서도 401이 오면 위 표와 같은 코드로 같은 화면에 보낸다(응답 인터셉터에서 `navigate`해도 된다).

## 4. 오류 응답

처리된 API 오류는 아래 형식이다. 화면 분기는 `code`로 하고, `message`는 그대로 띄워도 되는 한국어 문구다. 네트워크 단절·프록시 오류·처리되지 않은 서버 오류는 JSON이나 이 형식을 보장하지 않으므로 별도 기본 안내를 표시한다. 오류 객체 전체에는 요청 본문·헤더가 들어갈 수 있으므로 운영 콘솔이나 수집기로 그대로 보내지 않는다.

```json
{ "code": "OPEN_ACTIVITY_EXISTS", "message": "진행 중인 도움이 있어요." }
```

### 공통

| 상태 | code | 화면 처리 |
|---|---|---|
| 400 | `INVALID_INPUT` | 입력값 확인. 로그가 필요하면 상태·코드만 남김 |
| 401 | `GUEST_SESSION_REQUIRED` / `GUEST_SESSION_INVALID` | 3절 표 |
| 403 | `CSRF_REJECTED` | 인터셉터가 1회 재시도. 그래도 실패하면 토스트 |
| 403 | `FORBIDDEN` | CSRF 외 접근 거부. 토스트 |
| 404 | `NOT_FOUND` | "확인할 수 없는 항목" 안내 후 홈으로 (남의 것·삭제 중·만료 모두 404) |
| 409 | `WORRY_LOCKED` | 아직 공개 전. 잠금 화면 |
| 503 | `TEMPORARILY_UNAVAILABLE` | "잠시 후 다시" + 재시도 버튼 |

- 오류 응답은 전역 예외 처리(`ApiExceptionHandler`)가 같은 형식으로 만든다. JSON 파싱 실패, 필수 쿼리·헤더 누락, 경로 UUID 형식 오류도 모두 400 `INVALID_INPUT`이고, DB 오류는 503 `TEMPORARILY_UNAVAILABLE`이다.
- 오류 응답에는 `Cache-Control: no-store`가 붙는다.
- 존재하지 않는 API 경로는 404 `NOT_FOUND`, 잘못된 HTTP 메서드는 405 `METHOD_NOT_ALLOWED`(`Allow` 유지), JSON을 받을 수 없는 `Accept`는 406 `NOT_ACCEPTABLE`, 지원하지 않는 요청 본문 형식은 415 `UNSUPPORTED_MEDIA_TYPE`이다. 프록시가 이 상태를 200 HTML로 바꾸지 않아야 한다.

### 활동 (BE2)

| 상태 | code | 언제 | 화면 처리 |
|---|---|---|---|
| 409 | `OPEN_ACTIVITY_EXISTS` | 다른 도움이 진행 중인데 새 도움 시작 | **도움 변경 확인창** → 확인 시 `replaceOpen: true`로 다시 시작 |
| 409 | `ACTION_STEPS_INCOMPLETE` | 액션 3단계를 다 체크하기 전 완료 | 완료 버튼 비활성(`action.canComplete`)이 기본. 오면 활동 재조회 |
| 409 | `REST_NOT_FINISHED` | 휴식 시간이 남았는데 완료 | 활동 재조회 후 서버 남은 시간으로 타이머 재시작 |
| 409 | `REST_TIME_OVER` | 휴식 시간이 끝난 뒤 일시정지 | 타이머 0 처리, 완료 버튼 노출 |
| 409 | `ACTIVITY_ALREADY_ENDED` | 이미 완료·중단된 활동을 조작 | 활동 재조회 → 결과/홈으로 |
| 409 | `INVALID_ACTIVITY_STATE` | 유형·상태에 맞지 않는 조작(예: 액션에 pause) | 활동 재조회 후 화면 갱신 |
| 409 | `ACTIVITY_NOT_COMPLETED` | 완료 전 평가 저장 | 평가 UI는 `state === 'COMPLETED'`일 때만 노출 |

- 위 활동 코드는 `/api/v1/activities/...`와 `POST /worries/{worryId}/activities`(`Idempotency-Key` 없이 호출)에서 나온다. 6절 BE1 호환 경로는 이 코드들을 409 `ACTIVITY_CONFLICT` 하나로 묶어 돌려준다.

## 5. 활동 흐름

```js
// src/api/activity.js
import { api } from './client';

export const startActivity = (worryId, helpType, replaceOpen = false) =>
  api.post(`/worries/${worryId}/activities`, replaceOpen ? { helpType, replaceOpen } : { helpType });
export const getActivity   = (id) => api.get(`/activities/${id}`);
export const getCurrent    = () => api.get('/activities/current'); // 204면 이어할 활동 없음
export const setStep       = (id, stepNo, done) => api.put(`/activities/${id}/steps/${stepNo}`, { done });
export const pauseRest     = (id) => api.post(`/activities/${id}/pause`);
export const resumeRest    = (id) => api.post(`/activities/${id}/resume`);
export const stopActivity  = (id) => api.post(`/activities/${id}/stop`);
export const completeActivity = (id) => api.post(`/activities/${id}/complete`);
export const saveFeedback  = (id, answer) => api.put(`/activities/${id}/feedback`, { answer }); // HELPFUL | UNSURE | NOT_HELPFUL
```

1. **시작** `startActivity(worryId, 'ACTION' | 'REST' | 'EMPATHY')` — 새로 만들면 201, 같은 유형이 이미 열려 있거나 중단된 액션을 재개하면 200. 둘 다 같은 활동 응답.
   - `OPEN_ACTIVITY_EXISTS`면 확인창 → `startActivity(worryId, helpType, true)`. 응답의 `stoppedActivityId`가 중단된 기존 활동이다.
2. **진행**
   - ACTION: `setStep(id, 1~3, true/false)` → 응답의 `action.doneCount`, `action.canComplete`로 그린다.
   - REST: `pauseRest` / `resumeRest`. 서버는 시간이 다 돼도 자동 완료하지 않는다(`rest.timeOver: true`).
   - 그만두기·나중에: `stopActivity(id)` (액션 체크는 남고, 같은 걱정에서 ACTION을 다시 시작하면 이어진다).
3. **완료** `completeActivity(id)` — 응답 `nextUnviewedExists`로 "다음 걱정 보기" 노출 여부를 정한다.
4. **평가** `saveFeedback(id, 'HELPFUL')` — 다시 보내면 답만 바뀐다.

### 휴식 타이머는 서버 값 기준

- 카운트다운은 응답의 `rest.remainingSeconds`에서 시작한다. 기기 시계로 `endsAt`을 계산하지 않는다.
- 화면 복귀(`visibilitychange`), 새로고침, 일시정지/재개 직후에는 `getActivity(id)`로 **다시 조회해 타이머를 재시작**한다.
- 로컬 카운트가 0이 되면 바로 완료하지 말고 한 번 재조회해 `rest.timeOver`를 확인한 뒤 완료 버튼을 연다.

```js
const { data } = await getActivity(id);
startCountdown(data.rest.remainingSeconds); // 일시정지 상태면 remainingSeconds만 있고 endsAt은 null
```

- 홈 이어하기 카드는 `getCurrent()`: 200이면 `progress`(ACTION은 `doneCount/totalSteps`, REST는 `remainingSeconds/timeOver`, EMPATHY는 `null`), 204면 카드 숨김.

## 6. BE1 호환 경로 (새 화면에서 쓰지 않음)

BE1 초기 화면용 경로가 같은 활동 데이터를 다른 모양으로 돌려준다. 새 화면은 5절 경로만 쓴다.

| 경로 | 비고 |
|---|---|
| `POST /worries/{worryId}/activities` + `Idempotency-Key` 헤더 | `replaceOpen`이 없으면 호환 응답(`checkedSteps`, `remainingMillis`, `feedback`)으로 200. `replaceActive`도 받는다. 같은 키에 다른 `helpType`이면 409 `IDEMPOTENCY_CONFLICT` |
| `GET /worries/{worryId}/activities` | 걱정별 활동 이력(호환 응답 배열) |
| `POST /worries/{worryId}/activities/{activityId}/commands` | `{ command: CHECK \| PAUSE \| RESUME \| STOP \| COMPLETE, step?, checked? }` |
| `PUT /worries/{worryId}/activities/{activityId}/feedback` | `{ answer }` |

- 호환 경로의 상태 오류는 모두 409 `ACTIVITY_CONFLICT`다.
- 새 화면의 기본 호출에는 활동 시작용 `Idempotency-Key`를 생략한다. 키를 붙이고 `replaceOpen`을 생략한 경우에만 호환 응답이 된다. 최신 FE2처럼 `replaceOpen: false`를 명시하면 키가 있어도 새 응답을 받는다. 현재 FE2 호출 자체가 잘못된 것은 아니지만, 공통 인터셉터에서 키나 본문 필드를 임의로 추가·제거하지 않는다.

## 7. 금고·기록·회고·삭제

응답 본문 상세는 [openapi.yaml](openapi.yaml)을 본다. 공통 오류(4절)는 생략했다.

### 금고

| 메서드·경로 | 응답 | 주요 오류 |
|---|---|---|
| `GET /vault/summary` | 200 `{ lockedCount, publicCount, nearestPublishAt, analysisRequests }` | |
| `GET /worries?visibility=LOCKED\|PUBLIC&cursor&limit` | 200 `{ visibility, items, nextCursor }` | 400 `INVALID_INPUT`(`limit` 1~100 밖, 다른 목록의 커서) |
| `GET /worries/{worryId}` | 200 잠금이면 `LockedDetail`, 공개면 `PublicDetail`(`summary` 수정본 우선, `summaryEdited`, `analysis`에는 요약 없음) | 404 `NOT_FOUND` |
| `PATCH /worries/{worryId}/publication` `{ publishAt }` | 200 `LockedDetail` | 409 `ALREADY_PUBLIC`, 422 `INVALID_PUBLICATION`(지금 이전이거나 만료 이후) |
| `POST /worries/{worryId}/view` | 200 `{ firstViewedAt }` | 409 `WORRY_LOCKED` |

### 기록 (공개된 걱정)

| 메서드·경로 | 응답 | 주요 오류 |
|---|---|---|
| `GET /records?cursor&limit` | 200 `{ items, nextCursor }`, 항목에 `summaryEdited` 포함 | 400 `INVALID_INPUT` |
| `GET /records/{worryId}` | 200 `PublicDetail`. 활동 이력·평가는 `GET /worries/{worryId}/activities`, 회고는 `GET /reflections/{worryId}`로 따로 조회 | 404 `NOT_FOUND`(잠금 걱정 포함) |
| `GET /worries/{worryId}/past-experience` | 200 `{ worryId, summary, helpType, helpful }`, 비슷한 경험 없으면 204. 활동을 고르지 않은 기록이면 `helpType: null`. `helpful: true`면 `helpType`은 도움 된 활동의 유형 | 404 `NOT_FOUND`(현재 걱정이 잠김이어도 404) |
| `PUT /worries/{worryId}/summary` `{ summary }` | 204 | 400 `INVALID_INPUT`(빈 값·500자 초과), 409 `WORRY_LOCKED` |
| `PUT /worries/{worryId}/anxiety` `{ anxietyAfter: 1~5 }` | 204 | 400 `INVALID_INPUT`, 409 `WORRY_LOCKED` |

### 회고

`timeZone`은 IANA 이름(`Intl.DateTimeFormat().resolvedOptions().timeZone`)이다. 잘못된 값이면 400 `INVALID_INPUT`.

| 메서드·경로 | 응답 | 주요 오류 |
|---|---|---|
| `GET /reflections/candidate?currentWorryId&timeZone` | 200 `{ worryId, firstAvailableAt, summary, summaryEdited }`(요약은 수정본 우선), 후보 없으면 204 | |
| `POST /reflections/skip-today?timeZone` | 204 (오늘 하루 후보 숨김). 같은 날 판단은 후보 조회 때 보낸 `timeZone` 기준 | |
| `GET /reflections/{worryId}` | 200 `{ answer, respondedAt }`, 답 없으면 204 | 409 `WORRY_LOCKED` |
| `PUT /reflections/{worryId}?currentWorryId&timeZone` `{ answer: BETTER \| ONGOING \| AS_FEARED }` | 204 (같은 답 재전송도 204) | 409 `REFLECTION_UNAVAILABLE`(지금 후보가 아님), 409 `REFLECTION_ALREADY_ANSWERED`(이미 다른 답) |

### 삭제

| 메서드·경로 | 응답 | 주요 오류 |
|---|---|---|
| `DELETE /worries/{worryId}` | 204 삭제 완료, 202 `{ status: "DELETING" }` 처리 중 | 404 `NOT_FOUND` |
| `DELETE /vault` | 204, 게스트 쿠키 삭제 | |

- 서버는 같은 요청에서 정리까지 시도하고 완료되면 `204`를 반환한다. 정리가 끝나지 않으면 `202`와 `{ status: "DELETING" }`을 반환하며, 남은 정리는 백그라운드에서 재시도한다. 화면은 `202`를 처리 중으로 표시한다.
- 별도의 삭제 작업 ID나 작업 조회 API는 없다. 같은 게스트가 같은 기록에 `DELETE`를 다시 보내 `204`를 받으면 완료로 처리한다. 정리 중인 기록은 조회와 목록에서 먼저 숨겨질 수 있으므로 `404`·목록 제외·타임아웃만으로 완료 처리하지 않는다.
- 만료됐지만 아직 남아 있는 본인 기록도 같은 방식으로 정리한다. 이미 정리된 기록은 최근 30일 이내의 삭제 완료 기록이 있으면 `204`를 반환한다. 본인 기록이나 유효한 완료 기록을 찾을 수 없으면 `404 NOT_FOUND`를 반환한다.
- `DELETE /vault` 후에는 게스트 세션과 CSRF 토큰이 모두 없어진다. 3절 흐름(온보딩)으로 보내고, 다음 요청 전에 CSRF 토큰을 다시 받는다(`csrf = null`).

## 8. 배포 시 주의

- 이 가이드는 **프런트와 API가 같은 HTTPS 출처**(스킴·호스트·포트가 같음, 리버스 프록시: `https://app.example.com/` → 프런트, `/api/` → 백엔드)인 전제다. 그래서 CORS 설정이 없고 쿠키가 `SameSite=Lax`다.
- 운영 쿠키는 `Secure`라 **HTTPS**에서만 저장된다.
- 출처가 다르면 현재 CORS 미허용 때문에 브라우저 연동이 막힌다. 같은 사이트의 서브도메인 분리도 다른 출처이므로 CORS가 필요하지만, 그것만으로 `SameSite=Lax` 쿠키가 반드시 막히는 것은 아니다. 완전히 다른 사이트이면 `SameSite=None; Secure` 검토와 브라우저의 제3자 쿠키 제한 확인도 필요하다. 실제 주소를 정한 뒤 허용 출처·credentials·사용 헤더를 명시하며, credentials와 `Access-Control-Allow-Origin: *`를 함께 쓰지 않는다. [CORS 근거](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS), [쿠키 근거](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie).
