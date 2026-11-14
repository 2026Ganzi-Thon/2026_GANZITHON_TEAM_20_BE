# 프런트 연동 가이드

React + Vite + JavaScript + React Router + Axios 기준. 상세 스펙은 [openapi.yaml](openapi.yaml)을 본다.

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

export const api = axios.create({ baseURL: '/api/v1' });

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
    // 세션 발급 후 서버가 CSRF 토큰을 버리므로 다시 받는다
    if (res.config.url === '/guest-sessions' && res.config.method === 'post') {
      await fetchCsrf();
    }
    return res;
  },
  async (error) => {
    const { config, response } = error;
    if (response?.status === 403 && response.data?.code === 'CSRF_REJECTED' && !config._csrfRetried) {
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

모든 오류는 같은 형식이다. 화면 분기는 `code`로 하고, `message`는 그대로 띄워도 되는 한국어 문구다.

```json
{ "code": "OPEN_ACTIVITY_EXISTS", "message": "진행 중인 도움이 있어요." }
```

### 공통

| 상태 | code | 화면 처리 |
|---|---|---|
| 400 | `INVALID_INPUT` | 요청 값 버그. 토스트 + 콘솔 로그 |
| 401 | `GUEST_SESSION_REQUIRED` / `GUEST_SESSION_INVALID` | 3절 표 |
| 403 | `CSRF_REJECTED` | 인터셉터가 1회 재시도. 그래도 실패하면 토스트 |
| 404 | `NOT_FOUND` | "확인할 수 없는 항목" 안내 후 홈으로 (남의 것·삭제·만료 모두 404) |
| 409 | `WORRY_LOCKED` | 아직 공개 전. 잠금 화면 |
| 503 | `TEMPORARILY_UNAVAILABLE` | "잠시 후 다시" + 재시도 버튼 |

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

## 6. 배포 시 주의

- 이 가이드는 **프런트와 API가 같은 도메인**(리버스 프록시: `https://app.example.com/` → 프런트, `/api/` → 백엔드)인 전제다. 그래서 CORS 설정이 없고 쿠키가 `SameSite=Lax`다.
- 운영 쿠키는 `Secure`라 **HTTPS**에서만 저장된다.
- 프런트와 API를 **다른 도메인**에 두면 지금 설정으로는 동작하지 않는다(CORS 미허용, `SameSite=Lax` 쿠키 미전송). 이 경우 BE1 보안 설정(`GuestSecurityConfiguration`의 CORS, 쿠키 `SameSite`)을 바꿔야 하니 BE1과 먼저 협의한다.
