# NightVault 5·6단계 백엔드 API

게스트 쿠키로 인증하며 POST·PUT·PATCH·DELETE에는 `X-CSRF-TOKEN`을 보낸다. 기본 경로는 `/api/v1`이다. 기존 홈·금고·공개 시각·개별 삭제 계약은 [OpenAPI](openapi.yaml)를 따른다.

## 금고와 기록

- `GET /vault/summary`는 잠김·공개 개수, 다음 공개 시각, 진행 중 또는 최초 접수 후 24시간 이내 실패한 분석 요청을 돌려준다. 위기 안내는 홈에서 복원하지 않는다.
- `PATCH /worries/{worryId}/publication`에 `publishAt`을 ISO 오프셋 날짜·시각으로 보낸다. 잠겼고 새 시각이 현재보다 늦으며 만료 전이면 변경한다. 이미 공개됐으면 `409 ALREADY_PUBLIC`이다.
- `GET /records?limit=20&cursor=...`는 공개된 기록만 조회한다. 항목에는 `worryId`, `createdAt`, `summary`, `summaryEdited`, `anxietyBefore`, `anxietyAfter`, `anxietyDelta`, `latestHelpType`이 있다. `summary`는 수정본 우선이며 `summaryEdited`가 `true`면 출처를 '내가 정리했어요'로 표시한다. 변화량은 후 값에서 전 값을 뺀 값이며 후 값이 없으면 `null`이다. `GET /records/{worryId}`는 금고의 공개 상세와 같은 데이터를 반환한다. 실제 상세 진입 시 기존 `POST /worries/{worryId}/view`를 호출한다.
- `PUT /worries/{worryId}/summary`에 `{"summary":"수정 요약"}`, `PUT /worries/{worryId}/anxiety`에 `{"anxietyAfter":3}`을 보낸다. 성공하면 `204`다. 수정본과 불안도는 암호화한다. 요약을 수정하면 수정 여부와 시각(`summary_edited_at`)을 저장한다. AI 원본 요약은 서버에만 보관하고 공개 상세의 `analysis`에는 넣지 않는다(표시용은 상위 `summary`).

## 활동과 평가

- `POST /worries/{worryId}/activities`에 `{"helpType":"ACTION","replaceOpen":false}`를 보낸다. 새 활동은 `201`, 기존 열린 활동 또는 액션 재개는 `200`이다. 도움 변경에 동의하면 `replaceOpen: true`로 보낸다.
- `GET /activities/{activityId}`에서 활동과 진행 상태를 복원한다. `PUT /activities/{activityId}/steps/{stepNo}`는 `{"done":true}`를 받는다. `POST /activities/{activityId}/pause`, `/resume`, `/stop`, `/complete`는 [OpenAPI](openapi.yaml)의 상태 전이 규칙을 따른다.
- `PUT /activities/{activityId}/feedback`에 `{"answer":"HELPFUL"}`를 보낸다. 완료된 활동에서 `HELPFUL`, `UNSURE`, `NOT_HELPFUL` 중 하나를 저장할 수 있다. `GET /activities/current`는 홈 이어하기용 `progress`를 돌려주고, 이어갈 활동이 없으면 `204`를 돌려준다.
- BE1 호환 경로로 `GET /worries/{worryId}/activities`, `POST /worries/{worryId}/activities/{activityId}/commands`, `PUT /worries/{worryId}/activities/{activityId}/feedback`을 유지한다. UUID `Idempotency-Key`를 보내고 `replaceOpen`을 생략한 기존 시작 요청도 `200`과 BE1 응답 형식을 유지한다. 호환 응답의 `remainingMillis`는 통합된 타이머의 초를 밀리초로 환산한다. 도움 유형은 `ACTION`, `REST`, `EMPATHY`다. 휴식은 3분이 지난 뒤 완료할 수 있고, 유효하지 않은 상태 변경은 `409 ACTIVITY_CONFLICT`다.

## 회고와 과거 경험

- `GET /reflections/candidate?currentWorryId=...&timeZone=Asia/Seoul`은 실제 공개 가능 시각부터 24시간이 지나고, 읽었으며, 아직 답하지 않은 기록 중 가장 오래된 항목을 돌려준다. 현재 확인 중인 걱정은 후보에서 제외된다. 후보가 없으면 `204`다.
- `POST /reflections/skip-today?timeZone=Asia/Seoul`은 해당 현지 날짜의 후보를 숨긴다. 서버는 현지 날짜와 함께 시간대·서버 시각을 저장하며, 같은 날 다시 보내면 처음 값을 유지한다.
- `PUT /reflections/{worryId}?currentWorryId=...&timeZone=Asia/Seoul`에 `{"answer":"BETTER"}`를 보내 회고한다. `ONGOING`, `AS_FEARED`도 지원한다. 같은 답을 다시 보내면 `204`, 이미 다른 답을 했으면 `409 REFLECTION_ALREADY_ANSWERED`이며 처음 답을 유지한다. 지금 후보가 아니면 `409 REFLECTION_UNAVAILABLE`이다.
- `GET /reflections/{worryId}`로 기록 상세에 표시할 기존 회고 응답과 응답 시각을 조회한다. 답하지 않았으면 `204`다.
- `GET /worries/{worryId}/past-experience`는 같은 게스트의 공개·유효한 최근 기록에서 관련 경험 한 건을 반환한다. 관련성은 표시용 요약(수정본 우선)으로 계산하며 초기 기준은 최근 50건, 두 개 이상 공통 단어, Jaccard 유사도 0.2 이상이다. 활동을 고르지 않은 기록도 후보이며 이때 `helpType`은 `null`, `helpful`은 `false`다. 관련 기록이 없으면 `204`다.

## 삭제와 만료

- `DELETE /worries/{worryId}`는 삭제 시작을 저장해 조회를 막은 뒤 같은 요청에서 정리까지 시도한다. 끝나면 `204`, 정리가 일부 실패하면 `202`이며 정리 작업이 재처리한다. 파쇄와 일반 삭제가 이 경로를 공유한다. 만료됐지만 정리 전인 기록에 삭제를 요청해도 정리 후 `204`다. 만료된 기록은 서버에서 즉시 조회를 차단하고 정리 작업이 관련 활동·평가·회고·알림 행을 지운다. 정리 작업은 실패한 행을 건너뛰며 (만료 시각, ID) 순서로 끝까지 진행한다. 개별 삭제 확인 이력은 최대 30일을 기준으로 정리한다.
- `DELETE /vault`는 게스트의 기록·분석 요청·활동·회고·알림 및 인증 세션을 한 트랜잭션으로 없애고, 성공하면 `204`와 만료된 게스트 쿠키를 돌려준다. 클라이언트는 인증과 로컬 캐시를 정리하고 첫 안내로 이동한다.

## 공개 알림과 재알림

- `GET /notifications/settings`에서 `vapidPublicKey`, `publicationEnabled`, `subscriptionCount`를 읽는다. 브라우저 권한과 구독은 프런트엔드가 처리한다. `PUT /notifications/subscription`에 구독의 `endpoint`, `p256dh`, `auth`를 전달한다.
- `PUT /notifications/publication`에 `{"enabled":true}` 또는 `false`를 보낸다. 같은 공개 예정 시각의 알림은 구독별로 묶으며 발송 직전에 열람·삭제·만료·동의·구독을 검사한다.
- `GET /notifications/reminder`로 예약을 조회한다. `PUT /notifications/reminder`에 `worryId`, 가입한 `endpoint`, `minutes` 30 또는 60을 보내면 현재 사용자 예약 한 건을 설정한다. `DELETE /notifications/reminder`는 예약 취소, `DELETE /notifications/settings`는 공개 동의 해제 및 구독·재알림 제거다.
- Spring 환경에 `NIGHTVAULT_PUSH_PUBLIC_KEY`, `NIGHTVAULT_PUSH_PRIVATE_KEY`, `NIGHTVAULT_PUSH_SUBJECT`를 설정하면 VAPID 웹 푸시 전송이 활성화된다. 서비스 워커는 JSON의 `title`, `body`, `url`을 사용해 표시와 클릭 이동을 연결한다. `/vault`와 `/`는 프런트엔드와 맞출 상대 경로다. 실제 권한·수신·클릭 흐름은 브라우저에서 확인한다.
- 전송 실패는 서버 로그에 본문과 구독 정보 없이 기록한다. 브라우저 수신이나 열람까지 성공한 것으로 처리하지 않는다.
- 발송 대상 조건과 중복 여부는 선점 시 검사한다. 선점 기록을 커밋하고 발송 직전에 동의·구독·삭제·만료·열람 조건을 재검사한다. 마지막 재검사 후 취소와 전송이 겹칠 수 있으며, 선점 직후 서버가 종료되면 해당 전송을 놓칠 수 있다.
