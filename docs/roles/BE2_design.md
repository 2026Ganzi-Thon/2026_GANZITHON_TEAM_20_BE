# BE2 설계 초안

[BE2 개발 계획](BE2_README.md) · [BE2 기능 규칙](../spec/BE2_rules.md) · [화면 동작 정책](../spec/screen_policy.md) · [ERD](../erd/erd.md) · [공개 API](../api/openapi.yaml)

> 작성일 2026-11-14. 규칙 출처는 `docs/spec/BE2_rules.md`(기능명세서)와 `docs/spec/screen_policy.md`(화면 동작 정책)이다.
> 대조한 자료는 `docs/roles/BE2_README.md`, `docs/erd/erd.md`·`erd.dbml`, `docs/api/openapi.yaml`, V1·V2 마이그레이션, BE1 코드(`guest`, `analysis`, `api` 패키지)이다.
> **확인 필요**는 문서에 근거가 없어 추측한 부분이다. **BE1 합의 필요**는 BE1 소유 테이블·코드를 바꿔야 하는 부분이다.
> API는 초안이며 아직 `openapi.yaml`에는 반영하지 않았다.

---

## 0. 현재 코드 기준 전제

설계를 시작하기 전에 BE1 코드에서 확인한 사실이다.

| 항목 | 현재 상태 | BE2 설계에 주는 영향 |
| --- | --- | --- |
| 인증 주체 | `GuestAuthenticationFilter`가 `@AuthenticationPrincipal UUID guestId`와 `ROLE_GUEST`를 넣는다. | BE2 컨트롤러도 `UUID guestId`를 받는다. 시연 권한(`ROLE_DEMO`)은 이 필터를 확장해야 한다. **BE1 합의 필요** |
| CSRF | 모든 쓰기 요청에 `X-CSRF-TOKEN` 필요 | BE2 쓰기 API(POST/PUT/PATCH/DELETE)도 `csrfToken` 보안 스키마를 붙인다. |
| 오류 형식 | `ApiError` enum, `{code, message}` | BE2 오류 코드를 같은 enum에 추가하거나 BE2 enum을 따로 둔다. **BE1 합의 필요** |
| 걱정 원문·불안도 | `worries.input_ciphertext` 하나에 `WorryInput(text, anxiety, memo, publishAt)` JSON을 AES-GCM으로 저장. AAD는 `input:{requestId}` | 봉인 전 불안도(`anxietyBefore`)도 암호문 안에 있다. 기록 목록에서 불안도를 보여주려면 행마다 복호화해야 한다. |
| AI 콘텐츠 | `ai_contents.content_ciphertext`, AAD는 `content:{requestId}` | BE2는 요약·액션 3단계·휴식 안내·공감 메시지를 읽기만 한다. |
| 암호화 모듈 | `AnalysisPayloads`는 `analysis` 패키지 전용(package-private) | BE2가 복호화하고 수정 요약을 암호화하려면 공통 모듈로 분리해야 한다. **BE1 합의 필요** |
| `/worries` API | `openapi.yaml`에는 목록·상세·삭제·공개 시각 변경·열람이 있지만 **컨트롤러는 아직 없다** | 삭제(`DELETE /worries/{id}`)는 BE2 공통 삭제를 호출한다고 적혀 있다. 상세·열람은 누가 구현할지 정해야 한다. **확인 필요** |
| 잠금 순서 | `AnalysisJobStore.finish`가 `guests FOR UPDATE` → `analysis_requests FOR UPDATE` 순으로 잠근다. | BE2도 **게스트 → 걱정 → 활동** 순서로 잠근다. |
| 오류 코드 누락 | `openapi.yaml`의 `WORRY_LOCKED`, `ALREADY_PUBLIC`이 `ApiError` enum에 없다. | BE2도 `WORRY_LOCKED`를 쓰므로 enum 추가가 필요하다. **BE1 합의 필요** |
| 마이그레이션 | `V1__…`, `V2__…` 정수 버전 | 날짜 기반 버전으로 바꾸면 BE1도 이후 같은 규칙을 따라야 한다. 아래 §1-0 참고 |

---

## 1. 테이블 설계

### 1-0. 마이그레이션 파일 규칙

`src/main/resources/db/migration`에 `V{yyyyMMddHHmm}__{영문_snake_case}.sql` 형식으로 둔다. 시각은 작성 시점(KST)이다.

| 파일 예시 | 내용 | 담당 |
| --- | --- | --- |
| `V202611141530__add_worry_edit_columns.sql` | `worries` 수정 요약·수정 여부·현재 불안도 컬럼, `UNIQUE (id, guest_id)` | BE1 합의 필요 |
| `V202611141600__create_activity_tables.sql` | `activities`, `activity_feedback` | BE2 |
| `V202611141630__create_reflection_tables.sql` | `reflections`, `reflection_skips` | BE2 |
| `V202611141700__create_deletion_records.sql` | `deletion_records` | BE2 |

- Flyway는 버전을 숫자로 비교하므로 `202611141530`은 `2`보다 뒤에 적용된다. 기존 V1·V2는 바꾸지 않는다.
- **주의:** 날짜 버전을 한 번 적용한 DB에 BE1이 `V3__…`를 추가하면 Flyway 검증이 실패한다(`outOfOrder` 기본값 false). 이후 모든 마이그레이션을 날짜 버전으로 쓰기로 BE1과 합의해야 한다. **BE1 합의 필요**
- 이미 적용된 파일은 고치지 않고 새 파일로 변경한다.

### 1-1. worries 추가 컬럼 — **BE1 합의 필요**

`worries`는 BE1 소유 테이블이다. 아래 컬럼은 M-03·M-04 구현에 필요하다.

| 컬럼 | 타입 | NULL | 설명 | 암호화 |
| --- | --- | --- | --- | --- |
| `summary_edit_ciphertext` | `bytea` | 허용 | 사용자가 수정한 요약. AAD는 `summary-edit:{worryId}`를 제안한다. | **예** |
| `summary_edited_at` | `timestamptz` | 허용 | 마지막 수정 시각. **NULL이 아니면 수정 여부 = 예**로 본다. 별도 boolean을 두지 않는다. | 아니요 |
| `anxiety_after_ciphertext` | `bytea` | 허용 | 공개 후 현재 불안도(1~5). NULL이면 미기록 | **예 (확인 필요)** |
| `anxiety_after_updated_at` | `timestamptz` | 허용 | 현재 불안도 마지막 저장 시각 | 아니요 |

```sql
-- V202611141530__add_worry_edit_columns.sql (BE1 합의 후 작성)
ALTER TABLE worries
    ADD COLUMN summary_edit_ciphertext bytea,
    ADD COLUMN summary_edited_at timestamptz,
    ADD COLUMN anxiety_after_ciphertext bytea,
    ADD COLUMN anxiety_after_updated_at timestamptz,
    ADD CONSTRAINT worries_summary_edit_check
        CHECK ((summary_edit_ciphertext IS NULL) = (summary_edited_at IS NULL)),
    ADD CONSTRAINT worries_anxiety_after_check
        CHECK ((anxiety_after_ciphertext IS NULL) = (anxiety_after_updated_at IS NULL)),
    ADD CONSTRAINT worries_owner_unique UNIQUE (id, guest_id);

-- 회고 후보 조회용 (§3-1)
CREATE INDEX worries_guest_available_idx
    ON worries (guest_id, (GREATEST(sealed_at, publish_at)), id)
    WHERE deletion_started_at IS NULL;
```

- **현재 불안도 암호화 여부 (확인 필요):** 화면 정책 §11 "민감한 원문·파생 정보는 암호화"에 따라 암호문을 제안한다. 봉인 전 불안도도 이미 암호문 안에 있다. 평문 `smallint CHECK (BETWEEN 1 AND 5)`로 두면 목록 쿼리가 단순해지지만, 그 경우 봉인 전 불안도와 보호 수준이 달라진다.
- **AI 원본 보존:** `ai_contents`는 그대로 둔다. 수정본은 `worries`에만 저장하므로 "AI 원본 서버 보관·되돌리기 없음·재생성이 수정본을 덮지 않음"이 구조적으로 보장된다.
- `UNIQUE (id, guest_id)`는 BE2 테이블이 `(worry_id, guest_id)` 복합 FK로 소유자를 맞추려고 필요하다. `analysis_requests`와 같은 방식이다.

### 1-2. activities

걱정별 활동 이력이다. 파쇄는 걱정과 함께 삭제되므로 활동 행을 만들지 않는다. 기록은 `deletion_records`에 남긴다.

| 컬럼 | 타입 | NULL | 설명 |
| --- | --- | --- | --- |
| `id` | `uuid` | 아니요 | PK, `gen_random_uuid()` |
| `guest_id` | `uuid` | 아니요 | 소유자. 홈 이어하기 조회와 소유권 검사용 |
| `worry_id` | `uuid` | 아니요 | 대상 걱정 |
| `help_type` | `text` | 아니요 | `ACTION` / `REST` / `EMPATHY` (`SHRED` 제외) |
| `state` | `text` | 아니요 | `IN_PROGRESS` / `PAUSED` / `STOPPED` / `COMPLETED`. 시작 전은 행이 없다(§2-1). |
| `started_at` | `timestamptz` | 아니요 | 최초 시작 시각. 재진입·재개 때 바꾸지 않는다(ACT-01, §7-2). |
| `last_started_at` | `timestamptz` | 아니요 | 마지막으로 진행 중이 된 시각(생성·액션 재개). "최신 선택 도움" 정렬에 쓴다. |
| `state_changed_at` | `timestamptz` | 아니요 | 마지막 상태 변경 시각. 홈 이어하기 대표 선택에 쓴다. |
| `ended_at` | `timestamptz` | 허용 | 중단·완료 시각. 휴식 종료 시각 저장(REST-01)도 겸한다. |
| `step1_done`, `step2_done`, `step3_done` | `boolean` | 허용 | 액션 체크. `ACTION`일 때만 NOT NULL |
| `rest_duration_seconds` | `integer` | 허용 | `REST`일 때 180. 시연에서 짧게 바꿀 여지를 남긴다(확인 필요). |
| `rest_ends_at` | `timestamptz` | 허용 | 휴식 **실행 중**일 때만 값이 있다. 종료 예정 시각 |
| `rest_remaining_seconds` | `integer` | 허용 | 휴식 **일시정지 중**일 때만 값이 있다. 남은 초 |
| `version` | `integer` | 아니요 | 0부터 시작. 상태가 바뀔 때마다 1씩 올린다. 응답에 넣어 오래된 화면 상태를 확인한다. |

암호화 대상은 없다. 원문과 AI 내용은 `ai_contents`에서 읽고 활동 테이블에는 저장하지 않는다.

```sql
-- V202611141600__create_activity_tables.sql
CREATE TABLE activities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    guest_id uuid NOT NULL,
    worry_id uuid NOT NULL,
    help_type text NOT NULL,
    state text NOT NULL,
    started_at timestamptz NOT NULL,
    last_started_at timestamptz NOT NULL,
    state_changed_at timestamptz NOT NULL,
    ended_at timestamptz,
    step1_done boolean,
    step2_done boolean,
    step3_done boolean,
    rest_duration_seconds integer,
    rest_ends_at timestamptz,
    rest_remaining_seconds integer,
    version integer NOT NULL DEFAULT 0,
    CONSTRAINT activities_worry_owner_fk
        FOREIGN KEY (worry_id, guest_id) REFERENCES worries (id, guest_id) ON DELETE CASCADE,
    CONSTRAINT activities_help_type_check CHECK (help_type IN ('ACTION', 'REST', 'EMPATHY')),
    CONSTRAINT activities_state_check CHECK (state IN ('IN_PROGRESS', 'PAUSED', 'STOPPED', 'COMPLETED')),
    -- 일시정지는 휴식에만 있다.
    CONSTRAINT activities_pause_check CHECK (state <> 'PAUSED' OR help_type = 'REST'),
    CONSTRAINT activities_ended_check
        CHECK ((state IN ('STOPPED', 'COMPLETED')) = (ended_at IS NOT NULL)),
    CONSTRAINT activities_action_steps_check
        CHECK ((help_type = 'ACTION') = (step1_done IS NOT NULL AND step2_done IS NOT NULL AND step3_done IS NOT NULL)
           AND (help_type = 'ACTION' OR (step1_done IS NULL AND step2_done IS NULL AND step3_done IS NULL))),
    CONSTRAINT activities_action_complete_check
        CHECK (help_type <> 'ACTION' OR state <> 'COMPLETED' OR (step1_done AND step2_done AND step3_done)),
    CONSTRAINT activities_rest_duration_check
        CHECK ((help_type = 'REST') = (rest_duration_seconds IS NOT NULL)
           AND (rest_duration_seconds IS NULL OR rest_duration_seconds > 0)),
    -- 실행 중: ends_at만, 일시정지: remaining만, 그 외: 둘 다 없음
    CONSTRAINT activities_rest_timer_check CHECK (
        (help_type <> 'REST' AND rest_ends_at IS NULL AND rest_remaining_seconds IS NULL)
        OR (help_type = 'REST' AND state = 'IN_PROGRESS' AND rest_ends_at IS NOT NULL AND rest_remaining_seconds IS NULL)
        OR (help_type = 'REST' AND state = 'PAUSED' AND rest_ends_at IS NULL
            AND rest_remaining_seconds > 0 AND rest_remaining_seconds <= rest_duration_seconds)
        OR (help_type = 'REST' AND state IN ('STOPPED', 'COMPLETED')
            AND rest_ends_at IS NULL AND rest_remaining_seconds IS NULL)
    ),
    CONSTRAINT activities_version_check CHECK (version >= 0)
);

-- 같은 걱정에서 진행 중·일시정지 활동은 하나 (M-05, 중복 시작 방지)
CREATE UNIQUE INDEX activities_one_open_per_worry_idx
    ON activities (worry_id) WHERE state IN ('IN_PROGRESS', 'PAUSED');
-- 같은 걱정에서 이어할 수 있는 액션은 하나 (중단 액션은 같은 행을 재개)
CREATE UNIQUE INDEX activities_one_resumable_action_idx
    ON activities (worry_id) WHERE help_type = 'ACTION' AND state <> 'COMPLETED';
-- 홈 이어하기: 게스트의 최근 진행·일시정지 활동
CREATE INDEX activities_guest_open_idx
    ON activities (guest_id, state_changed_at DESC, id DESC) WHERE state IN ('IN_PROGRESS', 'PAUSED');
-- 기록 상세 이력·목록의 최신 선택 도움
CREATE INDEX activities_worry_history_idx ON activities (worry_id, last_started_at DESC, id DESC);
```

**휴식 타이머 계산 (REST-02·03)**

- 남은 시간 = 실행 중이면 `max(0, ceil(rest_ends_at - 서버 현재))`, 일시정지면 `rest_remaining_seconds`. 0 미만은 반환하지 않는다.
- 시작: `rest_ends_at = now + rest_duration_seconds`
- 일시정지: `rest_remaining_seconds = ceil(rest_ends_at - now)`, `rest_ends_at = NULL`. 남은 시간이 0 이하이면 정지하지 않고 409 `REST_TIME_OVER`를 반환한다.
- 재개: `rest_ends_at = now + rest_remaining_seconds`, `rest_remaining_seconds = NULL` (§7-3 "재개 시 종료 예정 시각을 다시 계산")
- 화면 이탈은 API 호출이 없으므로 실행 중이면 시간이 계속 흐르고 일시정지면 그대로 멈춰 있다.
- 0초가 돼도 서버는 상태를 바꾸지 않는다(자동 완료 아님). 응답에 `timeOver: true`만 계산해 넣는다.

### 1-3. activity_feedback

| 컬럼 | 타입 | NULL | 설명 |
| --- | --- | --- | --- |
| `activity_id` | `uuid` | 아니요 | PK, FK → `activities(id)` `ON DELETE CASCADE`. 활동당 평가 하나 |
| `answer` | `text` | 아니요 | `HELPFUL`(도움 됨) / `UNSURE`(잘 모르겠음) / `NOT_HELPFUL`(도움 안 됨) |
| `answered_at` | `timestamptz` | 아니요 | 최초 응답 시각 |
| `updated_at` | `timestamptz` | 아니요 | 마지막 변경 시각. 변경 허용 여부는 **확인 필요** |

```sql
CREATE TABLE activity_feedback (
    activity_id uuid PRIMARY KEY REFERENCES activities (id) ON DELETE CASCADE,
    answer text NOT NULL,
    answered_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT activity_feedback_answer_check CHECK (answer IN ('HELPFUL', 'UNSURE', 'NOT_HELPFUL'))
);
```

- 평가 행이 없으면 미평가다. 기본값을 두지 않는다(RESULT-02).
- 완료된 활동에만 저장한다. 테이블 간 조건이라 CHECK로 못 막으므로 서비스에서 활동 행을 잠그고 확인한다.
- 암호화 대상 아님(선택지 값만 저장). 단, 원문 없는 파생 정보도 암호화할지는 **확인 필요**

### 1-4. reflections

| 컬럼 | 타입 | NULL | 설명 |
| --- | --- | --- | --- |
| `worry_id` | `uuid` | 아니요 | PK. **회고 대상** 걱정 ID(REVIEW-02). 걱정당 회고 하나 |
| `guest_id` | `uuid` | 아니요 | 복합 FK로 소유자 일치 |
| `answer` | `text` | 아니요 | `GOT_BETTER`(괜찮아짐) / `ONGOING`(진행 중) / `AS_FEARED`(걱정대로 됨) |
| `responded_at` | `timestamptz` | 아니요 | 응답 시각 |

```sql
-- V202611141630__create_reflection_tables.sql
CREATE TABLE reflections (
    worry_id uuid PRIMARY KEY,
    guest_id uuid NOT NULL,
    answer text NOT NULL,
    responded_at timestamptz NOT NULL,
    CONSTRAINT reflections_worry_owner_fk
        FOREIGN KEY (worry_id, guest_id) REFERENCES worries (id, guest_id) ON DELETE CASCADE,
    CONSTRAINT reflections_answer_check CHECK (answer IN ('GOT_BETTER', 'ONGOING', 'AS_FEARED'))
);
```

- `ONGOING`이라는 이름은 활동 상태 `IN_PROGRESS`와 헷갈리지 않게 골랐다.

### 1-5. reflection_skips

"오늘은 건너뛰기"(REVIEW-03)를 게스트·현지 날짜 단위로 저장한다.

| 컬럼 | 타입 | NULL | 설명 |
| --- | --- | --- | --- |
| `guest_id` | `uuid` | 아니요 | PK 1, FK → `guests(id)` `ON DELETE CASCADE` |
| `local_date` | `date` | 아니요 | PK 2. **서버가** `statement_timestamp() AT TIME ZONE :timeZone`으로 계산한다. 클라이언트 날짜를 그대로 믿지 않는다. |
| `time_zone` | `text` | 아니요 | 건너뛸 때의 IANA 시간대 |
| `skipped_at` | `timestamptz` | 아니요 | 서버 시각 |

```sql
CREATE TABLE reflection_skips (
    guest_id uuid NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    local_date date NOT NULL,
    time_zone text NOT NULL,
    skipped_at timestamptz NOT NULL,
    PRIMARY KEY (guest_id, local_date),
    CONSTRAINT reflection_skips_time_zone_check CHECK (char_length(time_zone) BETWEEN 1 AND 100)
);
```

- 어떤 회고를 건너뛰었는지는 저장하지 않는다. 같은 날에는 어떤 회고도 제안하지 않으므로 대상 ID가 필요 없다.
- 2일이 지난 행은 정리 작업에서 지운다(확인 필요: 보관 기간 규칙 없음).
- 시간대가 바뀐 경우(예: 해외 이동) 오늘 날짜를 새 시간대로 계산하므로 같은 날 다시 제안될 수 있다. **확인 필요**

### 1-6. deletion_records

내용 없는 삭제 이력이다(D-03). 삭제 대상과 수명이 달라 FK를 두지 않는다.

| 컬럼 | 타입 | NULL | 설명 |
| --- | --- | --- | --- |
| `id` | `uuid` | 아니요 | PK |
| `owner_ref` | `uuid` | 아니요 | 삭제를 요청한 게스트 ID. FK 없음(게스트가 먼저 삭제될 수 있음). 완료 재확인 소유권 검사용 |
| `target_type` | `text` | 아니요 | `WORRY` / `GUEST` |
| `target_id` | `uuid` | 아니요 | 걱정 ID 또는 게스트 ID |
| `reason` | `text` | 아니요 | `SHRED` / `LOCKED_DELETE` / `RECORD_DELETE` / `EXPIRED` / `GUEST_DELETE` / `DEMO_RESET`. METRIC-01 집계용 |
| `state` | `text` | 아니요 | `PROCESSING` / `COMPLETED` |
| `attempt_count` | `integer` | 아니요 | 정리 시도 횟수 |
| `next_attempt_at` | `timestamptz` | 허용 | 부분 실패 재처리 예정 시각. `PROCESSING`일 때만 |
| `last_error_code` | `text` | 허용 | 내용 없는 오류 분류(`DB_TIMEOUT` 등). 원문·예외 메시지 저장 금지 |
| `requested_at` | `timestamptz` | 아니요 | 삭제 시작 시각 |
| `completed_at` | `timestamptz` | 허용 | 완료 시각 |
| `purge_after` | `timestamptz` | 허용 | `completed_at + 30일`. 이후 이력 자체를 지운다. |

```sql
-- V202611141700__create_deletion_records.sql
CREATE TABLE deletion_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_ref uuid NOT NULL,
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    reason text NOT NULL,
    state text NOT NULL DEFAULT 'PROCESSING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error_code text,
    requested_at timestamptz NOT NULL,
    completed_at timestamptz,
    purge_after timestamptz,
    CONSTRAINT deletion_records_target_unique UNIQUE (target_type, target_id),
    CONSTRAINT deletion_records_target_type_check CHECK (target_type IN ('WORRY', 'GUEST')),
    CONSTRAINT deletion_records_reason_check CHECK (reason IN
        ('SHRED', 'LOCKED_DELETE', 'RECORD_DELETE', 'EXPIRED', 'GUEST_DELETE', 'DEMO_RESET')),
    CONSTRAINT deletion_records_state_check CHECK (state IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT deletion_records_completion_check CHECK (
        (state = 'PROCESSING' AND completed_at IS NULL AND purge_after IS NULL AND next_attempt_at IS NOT NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND purge_after IS NOT NULL AND next_attempt_at IS NULL)),
    CONSTRAINT deletion_records_attempt_check CHECK (attempt_count >= 0)
);

CREATE INDEX deletion_records_retry_idx ON deletion_records (next_attempt_at, id) WHERE state = 'PROCESSING';
CREATE INDEX deletion_records_purge_idx ON deletion_records (purge_after) WHERE state = 'COMPLETED';
CREATE INDEX deletion_records_owner_idx ON deletion_records (owner_ref, target_id);
```

### 1-7. 암호화 대상 요약

| 데이터 | 위치 | 암호화 | 비고 |
| --- | --- | --- | --- |
| 원문·메모·봉인 전 불안도 | `worries.input_ciphertext` | 예 (BE1) | BE2는 복호화만 |
| AI 요약·추천·액션·휴식·공감 | `ai_contents.content_ciphertext` | 예 (BE1) | BE2는 복호화만 |
| 수정 요약 | `worries.summary_edit_ciphertext` | 예 | BE1 합의 필요 |
| 현재 불안도 | `worries.anxiety_after_ciphertext` | 예 (확인 필요) | BE1 합의 필요 |
| 활동 상태·체크·타이머 | `activities` | 아니요 | 내용 없음 |
| 평가·회고 응답 | `activity_feedback`, `reflections` | 아니요 (확인 필요) | 선택지 값만 |
| 건너뛰기·삭제 이력 | `reflection_skips`, `deletion_records` | 아니요 | 내용 없음 |

---

## 2. 활동 상태 전이

### 2-1. 상태 정의

| 기능명세 상태 | DB 값 | 의미 | 행 존재 |
| --- | --- | --- | --- |
| 시작 전 | (없음) | 휴식 시작 전 화면, 아직 고르지 않음 | **행 없음.** §7-3 "다음에 할게요는 활동을 만들지 않음"을 따른다. |
| 진행 중 | `IN_PROGRESS` | 실행 중 | 있음 |
| 일시정지 | `PAUSED` | 휴식 정지(휴식만) | 있음 |
| 중단 | `STOPPED` | 나중에 이어하기·그만두기·도움 변경 | 있음 |
| 완료 | `COMPLETED` | 사용자 완료 확인 + 저장 성공 | 있음 |

### 2-2. 전이표

빈칸은 허용하지 않는 전이다(409 `INVALID_ACTIVITY_STATE`).

| 현재 상태 → 이벤트 | 시작/진입 | 체크 변경 | 일시정지 | 재개 | 중단 | 완료 | 화면 이탈 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **시작 전 (행 없음)** | ACTION: S-10 진입 시 `IN_PROGRESS` 생성<br>REST: "시작하기" 시 `IN_PROGRESS` 생성, `ends_at = now + 180초`<br>EMPATHY: S-12 진입 시 `IN_PROGRESS` 생성 (확인 필요) | | | | | | 변화 없음 (REST "다음에 할게요"는 호출 없음) |
| **IN_PROGRESS** | 같은 유형 재진입: 기존 행 반환(200), `started_at` 유지 | ACTION만. 순서 자유, 해제 가능 | REST만. 남은 시간 > 0이면 `PAUSED`, 0이면 409 `REST_TIME_OVER` | 같은 상태면 200(멱등) | `STOPPED`. ACTION 체크 유지. REST 타이머 값 버림 | ACTION: 3단계 모두 체크면 `COMPLETED`, 아니면 409 `ACTION_STEPS_INCOMPLETE`<br>REST: `ends_at <= now`면 `COMPLETED`, 아니면 409 `REST_NOT_FINISHED`<br>EMPATHY: "읽었어요" → `COMPLETED` | 변화 없음. REST 시간 계속 흐름 |
| **PAUSED** (REST) | 재진입: 기존 행 반환 | | 같은 상태면 200(멱등) | `IN_PROGRESS`, `ends_at = now + remaining` | `STOPPED`, 남은 시간 버림 | 409 `REST_NOT_FINISHED` | 변화 없음. 정지 유지 |
| **STOPPED** | ACTION: 같은 행을 `IN_PROGRESS`로 재개, 체크 유지, `last_started_at` 갱신<br>REST·EMPATHY: 재개 불가. 다시 고르면 **새 행** (REST는 새 3분) | | | ACTION만 = 위 "시작" | 같은 상태면 200(멱등) | | — |
| **COMPLETED** | 같은 유형을 다시 고르면 새 행 (확인 필요) | | | | 409 `ACTIVITY_ALREADY_ENDED` | 같은 상태면 200(멱등) | — |

- 확인창(M-05 도움 변경, REST-04 그만두기)을 여는 것만으로는 서버를 호출하지 않는다. 그래서 확인창이 열린 동안에도 휴식 시간이 흐르고 0초 도달이 반영된다(§7-3).
- 평가는 상태 전이가 아니라 `COMPLETED` 활동에만 붙는 별도 저장이다.

### 2-3. 도움 변경 (M-05)

`POST /worries/{worryId}/activities`에 `replaceOpen` 플래그를 둔다.

1. 같은 걱정에 열린 활동(`IN_PROGRESS`/`PAUSED`)이 없으면 바로 시작한다.
2. 열린 활동이 **같은 유형**이면 그 활동을 200으로 반환한다(재진입).
3. 열린 활동이 **다른 유형**이고 `replaceOpen=false`면 409 `OPEN_ACTIVITY_EXISTS`를 반환한다. 프런트는 이 응답을 받고 확인창을 띄운다. 취소하면 아무 호출도 하지 않으므로 기존 상태가 유지된다.
4. 사용자가 확인하면 `replaceOpen=true`로 다시 호출한다. 한 트랜잭션에서 기존 활동을 `STOPPED`로 바꾸고 새 활동을 시작한다.
   - 기존이 ACTION이면 체크를 유지한다.
   - 기존이 REST면 `rest_ends_at`·`rest_remaining_seconds`를 비운다. 다시 고르면 새 3분이다.
   - 새 유형이 ACTION이고 같은 걱정에 `STOPPED` 액션이 있으면 그 행을 재개한다.
5. **파쇄 선택 (확인 필요):** 정책은 "변경 확인 후 별도 삭제 확인"이다. 변경 확인 시점에 기존 활동을 먼저 중단하면, 사용자가 삭제 확인에서 취소했을 때 활동만 중단된 채 남는다. 그래서 **파쇄는 활동 API를 호출하지 않고** 삭제 확인 후 `DELETE /worries/{id}`만 호출하도록 제안한다. 삭제되면 활동도 함께 지워진다.
6. 과거 활동 행은 지우지 않는다(이력 보존).

### 2-4. 중복 시작과 경쟁 처리

- **재전송·재진입:** 부분 유일 인덱스 `activities_one_open_per_worry_idx`가 같은 걱정의 열린 활동을 하나로 막는다. INSERT가 유일 제약 위반이면 다시 읽어 같은 유형이면 200, 다른 유형이면 409 `OPEN_ACTIVITY_EXISTS`를 반환한다.
- **완료와 중단이 동시에 도착:** 모든 전이는 조건부 UPDATE로 처리한다.
  ```sql
  UPDATE activities SET state = 'COMPLETED', ended_at = statement_timestamp(),
         state_changed_at = statement_timestamp(), version = version + 1
  WHERE id = :id AND guest_id = :guestId AND state = 'IN_PROGRESS' AND <완료 조건>
  RETURNING ...;
  ```
  0행이면 다시 읽는다. 이미 목표 상태면 200(멱등), 다른 종료 상태면 409 `ACTIVITY_ALREADY_ENDED`를 반환한다. 먼저 커밋한 요청이 이긴다.
- **활동 쓰기와 삭제가 동시에 실행:** 활동 쓰기는 `guests FOR SHARE` → `worries FOR SHARE`(삭제 시작·만료 아님 확인) → 활동 순서로 잠근다. 삭제는 `guests`(전체 삭제는 `FOR UPDATE`) → `worries FOR UPDATE` 순서라서 서로 기다린다. BE1 `finish()`의 게스트 우선 잠금 순서와 같다.
- **오래된 화면:** 응답에 `version`을 넣는다. 체크 변경은 단계별 PUT(멱등)이라 버전 없이도 마지막 값이 남는다. 버전 불일치 409를 도입할지는 **확인 필요**(초안에서는 사용하지 않음).

### 2-5. 화면 이탈과 복원

- 이탈 자체는 서버 이벤트가 아니다. 복귀 시 `GET /activities/{id}`로 서버 상태를 다시 읽는다(HOME-05).
- 응답에 `serverTime`과 `remainingSeconds`를 함께 넣는다. 프런트는 기기 시각이 아니라 이 값으로 카운트다운을 다시 시작한다.
- 홈 이어하기(HOME-03)는 게스트의 열린 활동 중 `state_changed_at`이 가장 최근인 것 하나를 대표로 보여준다. 중단된 활동은 홈에 나오지 않고 기록 상세에서 재개한다(액션만, §8-1).

---

## 3. 조회 쿼리 초안

공통 조건: 모든 조회에 `guest_id = :guestId AND deletion_started_at IS NULL AND expires_at > statement_timestamp()`를 넣는다. 공개 여부는 `publish_at <= statement_timestamp()`로 판정한다(기기 시각 사용 금지).

### 3-1. 회고 후보 (REVIEW-01)

```sql
SELECT w.id, w.created_at, GREATEST(w.sealed_at, w.publish_at) AS available_at
FROM worries w
WHERE w.guest_id = :guestId
  AND w.id <> :currentWorryId                      -- 현재 확인 중인 걱정 제외
  AND w.deletion_started_at IS NULL                -- 미삭제
  AND w.expires_at > statement_timestamp()         -- 미만료
  AND w.first_viewed_at IS NOT NULL                -- 상세 열람 완료
  AND GREATEST(w.sealed_at, w.publish_at) + INTERVAL '24 hours' <= statement_timestamp()
  AND NOT EXISTS (SELECT 1 FROM reflections r WHERE r.worry_id = w.id)   -- 미응답
  AND NOT EXISTS (                                  -- 오늘 건너뛰기 없음
      SELECT 1 FROM reflection_skips s
      WHERE s.guest_id = :guestId
        AND s.local_date = (statement_timestamp() AT TIME ZONE :timeZone)::date)
ORDER BY GREATEST(w.sealed_at, w.publish_at), w.id  -- 가장 오래된 최초 공개 가능 시각 우선
LIMIT 1;
```

- 최초 공개 가능 시각은 `GREATEST(sealed_at, publish_at)`이다(ERD, §8-2). 24시간 조건이 공개 조건도 포함한다.
- `:timeZone`은 요청 파라미터로 받은 IANA 식별자다. 잘못된 값이면 400이다. Java에서 `ZoneId.of`로 먼저 검증한다.
- 결과 행의 요약은 수정본을 우선해서 복호화한다.

### 3-2. 과거 경험 후보 (PAST-01·02)

관련성 기준은 아직 정해지지 않았다(BE2_rules 확인 필요 항목). 요약이 암호문이라 SQL에서 내용을 비교할 수 없으므로 **SQL로 후보를 좁히고 Java에서 관련성을 계산**하는 방식을 제안한다.

```sql
SELECT w.id, w.created_at, w.request_id, w.summary_edit_ciphertext, c.content_ciphertext,
       EXISTS (
           SELECT 1 FROM activities a JOIN activity_feedback f ON f.activity_id = a.id
           WHERE a.worry_id = w.id AND f.answer = 'HELPFUL'
       ) AS has_helpful,
       (SELECT a.help_type FROM activities a JOIN activity_feedback f ON f.activity_id = a.id
        WHERE a.worry_id = w.id AND f.answer = 'HELPFUL'
        ORDER BY f.answered_at DESC LIMIT 1) AS helpful_type
FROM worries w
JOIN ai_contents c ON c.worry_id = w.id
WHERE w.guest_id = :guestId
  AND w.id <> :currentWorryId                      -- 현재 제외
  AND w.deletion_started_at IS NULL
  AND w.expires_at > statement_timestamp()
  AND w.publish_at <= statement_timestamp()        -- 잠김 제외 (분석 중·실패는 worries에 없음)
ORDER BY w.created_at DESC, w.id DESC
LIMIT 50;                                          -- 보관 30일이라 후보 수가 작다 (상한 확인 필요)
```

Java 처리 (전부 **확인 필요**):

1. 현재 걱정과 후보의 요약(수정본 우선)을 복호화한다.
2. 관련성 점수를 계산한다. 예: 공백·문장부호를 뺀 글자 2-gram의 Jaccard 유사도
3. 점수가 임계값(예: 0.2) 미만이면 버린다. 남은 후보가 없으면 204(카드 숨김)
4. 점수가 높은 순, 같으면 `has_helpful` 우선, 그다음 최신순으로 하나를 고른다.
5. `has_helpful`이 true일 때만 응답의 `helpedBefore: true`와 `helpfulHelpType`을 채운다(PAST-02).

- 대안: AI가 봉인 때 키워드를 만들어 별도 테이블에 저장하는 방식. 이 경우 "검색 파생 데이터"가 되므로 삭제 범위에 넣어야 한다(§11). AI·BE1과 합의 필요
- 카드 선택 시 S-14 상세 API가 삭제·만료를 다시 검사한다.

### 3-3. 기록 목록 (R-01)

```sql
SELECT w.id, w.request_id, w.created_at, w.publish_at, w.first_viewed_at,
       w.input_ciphertext, w.summary_edit_ciphertext, w.anxiety_after_ciphertext,
       c.content_ciphertext,
       la.help_type AS latest_help_type, la.state AS latest_help_state
FROM worries w
JOIN ai_contents c ON c.worry_id = w.id
LEFT JOIN LATERAL (
    SELECT a.help_type, a.state FROM activities a
    WHERE a.worry_id = w.id
    ORDER BY a.last_started_at DESC, a.id DESC
    LIMIT 1
) la ON true
WHERE w.guest_id = :guestId
  AND w.deletion_started_at IS NULL
  AND w.expires_at > statement_timestamp()
  AND w.publish_at <= statement_timestamp()
  AND (:cursorCreatedAt IS NULL OR (w.created_at, w.id) < (:cursorCreatedAt, :cursorId))
ORDER BY w.created_at DESC, w.id DESC
LIMIT :limitPlusOne;
```

- 기존 인덱스 `worries_guest_created_idx (guest_id, created_at DESC, id DESC)`를 그대로 쓴다.
- 봉인 전 불안도는 `input_ciphertext`, 요약은 수정본 → AI 원본 순으로 복호화한다. 행당 복호화 2~3회, 페이지 20건 기준으로 문제없다고 본다.
- 최신 선택 도움이 없으면 `latestHelpType: null`("아직 선택하지 않음"). 파쇄는 기록에 남지 않으므로 목록에 나올 일이 없다.

### 3-4. 다음 미확인 걱정 존재 여부 (RESULT-03)

```sql
SELECT EXISTS (
    SELECT 1 FROM worries w
    WHERE w.guest_id = :guestId AND w.id <> :currentWorryId
      AND w.deletion_started_at IS NULL AND w.expires_at > statement_timestamp()
      AND w.publish_at <= statement_timestamp()
      AND w.first_viewed_at IS NULL
);
```

### 3-5. 홈 이어하기 (HOME-03)

```sql
SELECT a.*
FROM activities a
JOIN worries w ON w.id = a.worry_id
WHERE a.guest_id = :guestId
  AND a.state IN ('IN_PROGRESS', 'PAUSED')
  AND w.deletion_started_at IS NULL AND w.expires_at > statement_timestamp()
ORDER BY a.state_changed_at DESC, a.id DESC
LIMIT 1;
```

### 3-6. 만료 대상 선점 (D-02)

```sql
SELECT w.id, w.guest_id
FROM worries w
WHERE w.expires_at <= statement_timestamp() AND w.deletion_started_at IS NULL
ORDER BY w.expires_at, w.id
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

만료 행은 이 작업이 처리하기 전에도 모든 조회 쿼리의 `expires_at > statement_timestamp()` 조건으로 이미 차단된다(D-02 "삭제 지연 중에도 접근 차단").

---

## 4. API 초안

서버 기준 경로는 `/api/v1`이다. 공통 응답 규칙은 `openapi.yaml`을 따른다.

- 인증 없음·무효는 401 `GUEST_SESSION_REQUIRED`/`GUEST_SESSION_INVALID`, CSRF 실패는 403 `CSRF_REJECTED`
- 다른 게스트·삭제 중·만료 대상은 404 `NOT_FOUND`
- 일시 장애는 503 `TEMPORARILY_UNAVAILABLE`
- 모든 응답에 `Cache-Control: no-store`. 시간은 UTC RFC 3339

아래에는 공통 응답(401·403·404·503)을 생략하고 BE2 고유 응답만 적었다.

### 4-1. 경로 목록

| # | 메서드·경로 | 용도 | 기능 ID |
| --- | --- | --- | --- |
| 1 | `PATCH /worries/{worryId}/summary` | AI 정리 수정 | M-04 |
| 2 | `PUT /worries/{worryId}/anxiety-after` | 현재 불안도 저장·덮어쓰기 | M-03 |
| 3 | `POST /worries/{worryId}/activities` | 도움 선택·활동 시작·도움 변경·액션 재개 | M-05, ACT-01, ACT-03, REST-01, EMP-02 |
| 4 | `GET /activities/{activityId}` | 활동 상태 복원 | ACT-01, REST-03, HOME-05 |
| 5 | `PUT /activities/{activityId}/steps/{stepNo}` | 액션 체크 저장·해제 | ACT-02 |
| 6 | `POST /activities/{activityId}/pause` | 휴식 일시정지 | REST-02 |
| 7 | `POST /activities/{activityId}/resume` | 휴식 재개 | REST-02 |
| 8 | `POST /activities/{activityId}/stop` | 중단(나중에 이어하기·그만두기) | ACT-03, REST-04 |
| 9 | `POST /activities/{activityId}/complete` | 완료(액션 전체 완료·휴식 완료·읽었어요) | ACT-04, REST-04, EMP-02, RESULT-01, RESULT-03 |
| 10 | `PUT /activities/{activityId}/feedback` | 도움 평가 | RESULT-02 |
| 11 | `GET /activities/current` | 홈 이어하기 | HOME-03 |
| 12 | `GET /records` | 기록 목록 | R-01 |
| 13 | `GET /records/{worryId}` | 기록 상세·활동 이력·불안도 비교 | R-02, R-03 |
| 14 | `GET /worries/{worryId}/reflection-candidate` | 회고 후보 | REVIEW-01 |
| 15 | `PUT /worries/{worryId}/reflection` | 회고 응답(경로 ID = 회고 대상) | REVIEW-02 |
| 16 | `POST /reflection-skips` | 오늘은 건너뛰기 | REVIEW-03 |
| 17 | `GET /worries/{worryId}/past-experience` | 과거 경험 카드 | PAST-01, PAST-02 |
| 18 | `DELETE /worries/{worryId}` | 파쇄·잠긴 걱정 삭제·기록 삭제 (기존 경로) | H-02, LOCK-02, R-04, D-01 |
| 19 | `DELETE /guests/current` | 전체 삭제 (경로 BE1 합의 필요) | SET-05 |
| 20 | `GET /demo/status` | 시연 권한·상태 확인 | DEMO-01 |
| 21 | `POST /demo/time-machine` | 시연 기록 시간 이동 | DEMO-02 |
| 22 | `POST /demo/presets` | 시연 프리셋 생성 | DEMO-03 |
| 23 | `POST /demo/reset` | 시연 데이터 초기화 | DEMO-03 |

- 기록 목록·상세(#12·#13)를 `/worries`와 따로 둔 이유: S-07용 `GET /worries`(BE1)는 공개 목록 계약이 이미 있고, S-14에는 현재 불안도·최신 도움·활동 이력이 추가로 필요하다. 하나로 합칠지는 **BE1 합의 필요**
- 열람 기록은 기존 `POST /worries/{worryId}/view`를 S-14 상세 진입 때도 그대로 호출한다(R-02 "공통 저장").
- `GET /worries/{worryId}`(S-08 상세)의 `summary`는 수정본 우선, 응답에 `summaryEdited`를 추가해야 출처 문구('내가 정리했어요')를 표시할 수 있다. **BE1 합의 필요**

### 4-2. 신규 오류 코드

| 코드 | HTTP | 메시지(초안) | 사용 |
| --- | --- | --- | --- |
| `WORRY_LOCKED` | 409 | 아직 공개되지 않았어요. | 공개 전 걱정에 수정·활동·회고 요청. openapi에 있으나 `ApiError`에 없음 |
| `OPEN_ACTIVITY_EXISTS` | 409 | 진행 중인 도움이 있어요. | 다른 유형의 열린 활동이 있는데 `replaceOpen=false` |
| `INVALID_ACTIVITY_STATE` | 409 | 지금 상태에서는 할 수 없어요. | 허용하지 않는 전이 (예: 액션 일시정지) |
| `ACTIVITY_ALREADY_ENDED` | 409 | 이미 끝난 활동이에요. | 완료 후 중단, 중단 후 완료 등 |
| `ACTION_STEPS_INCOMPLETE` | 409 | 세 단계를 모두 마치면 완료할 수 있어요. | ACT-04 |
| `REST_NOT_FINISHED` | 409 | 아직 휴식 시간이 남았어요. | 0초 전 휴식 완료 |
| `REST_TIME_OVER` | 409 | 휴식 시간이 끝났어요. | 0초 이후 일시정지 |
| `ACTIVITY_NOT_COMPLETED` | 409 | 완료한 활동만 평가할 수 있어요. | 미완료 활동 평가 |
| `REFLECTION_NOT_AVAILABLE` | 409 | 지금은 돌아볼 수 없는 기록이에요. | 회고 조건 미충족 |
| `REFLECTION_ALREADY_ANSWERED` | 409 | 이미 답한 기록이에요. | 다른 답으로 재응답 (확인 필요) |
| `DEMO_ONLY` | 403 | 이 요청을 처리할 권한이 없어요. | 시연 권한 없음. 기존 `FORBIDDEN` 재사용 가능 (확인 필요) |

입력 형식 오류(글자 수, enum 값, 단계 번호)는 기존 400 `INVALID_INPUT`을 쓴다.

### 4-3. 요청·응답 예시 (openapi 스타일)

```yaml
paths:
  /worries/{worryId}/summary:
    parameters:
    - $ref: '#/components/parameters/WorryId'
    patch:
      tags: [Record]
      operationId: updateWorrySummary
      summary: AI 정리 수정 (M-04)
      description: 원문과 AI 원본은 바꾸지 않는다. 수정본은 목록·상세·회고·과거 경험에 우선 적용한다. 추천·콘텐츠를 다시 만들지 않는다.
      security:
      - guestCookie: []
        csrfToken: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [summary]
              properties:
                summary:
                  $ref: '#/components/schemas/WorryText'   # 공백 제외 1자 이상, 공백 포함 500자 이하
            example:
              summary: 발표 첫 문장을 잊을까 걱정했다.
      responses:
        '200':
          content:
            application/json:
              example:
                worryId: 1c25f56a-0b86-41d3-8f8c-2f7c19e7d842
                summary: 발표 첫 문장을 잊을까 걱정했다.
                summaryEdited: true
                summaryEditedAt: '2026-11-12T00:10:00Z'
        '400': { $ref: '#/components/responses/InvalidInput' }
        '409': { description: WORRY_LOCKED }

  /worries/{worryId}/anxiety-after:
    put:
      operationId: putAnxietyAfter
      summary: 현재 불안도 저장 (M-03)
      description: 1~5, 기본값 없음. 다시 저장하면 덮어쓴다. 건너뛰기는 호출하지 않는다. 도움 평가와 별개다.
      security: [{ guestCookie: [], csrfToken: [] }]
      requestBody:
        content:
          application/json:
            example: { anxiety: 2 }
      responses:
        '200':
          content:
            application/json:
              example: { worryId: 1c25f56a-0b86-41d3-8f8c-2f7c19e7d842, anxietyAfter: 2, updatedAt: '2026-11-12T00:12:00Z' }
        '400': { $ref: '#/components/responses/InvalidInput' }
        '409': { description: WORRY_LOCKED }

  /worries/{worryId}/activities:
    post:
      operationId: startActivity
      summary: 도움 선택·활동 시작 (M-05, ACT-01, REST-01, EMP-02)
      description: |-
        ACTION은 S-10 진입 시, REST는 "시작하기" 선택 시, EMPATHY는 S-12 진입 시 호출한다.
        SHRED는 받지 않는다(DELETE /worries/{worryId} 사용).
        같은 유형의 열린 활동이 있으면 새로 만들지 않고 200으로 반환한다.
        중단된 ACTION이 있으면 같은 활동을 재개한다(체크 유지).
        다른 유형의 열린 활동이 있으면 replaceOpen=true일 때만 기존 활동을 중단하고 시작한다.
      security: [{ guestCookie: [], csrfToken: [] }]
      requestBody:
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [helpType]
              properties:
                helpType: { type: string, enum: [ACTION, REST, EMPATHY] }
                replaceOpen: { type: boolean, default: false }
            example: { helpType: REST, replaceOpen: false }
      responses:
        '201':
          description: 새 활동 시작
          headers: { Location: { description: /api/v1/activities/{activityId} } }
          content:
            application/json:
              example:
                activityId: 7a0b8e4c-5d1f-4f0e-9a51-3f0f3c2b9d10
                worryId: 1c25f56a-0b86-41d3-8f8c-2f7c19e7d842
                helpType: REST
                state: IN_PROGRESS
                startedAt: '2026-11-12T00:15:00Z'
                endedAt: null
                version: 0
                serverTime: '2026-11-12T00:15:00Z'
                rest: { durationSeconds: 180, endsAt: '2026-11-12T00:18:00Z', remainingSeconds: 180, timeOver: false }
                stoppedActivityId: null
        '200': { description: 기존 열린 활동 반환 또는 중단된 액션 재개 (본문은 201과 같음) }
        '400': { description: INVALID_INPUT (SHRED 포함) }
        '409':
          description: OPEN_ACTIVITY_EXISTS 또는 WORRY_LOCKED
          content:
            application/json:
              example: { code: OPEN_ACTIVITY_EXISTS, message: 진행 중인 도움이 있어요. }

  /activities/{activityId}:
    get:
      operationId: getActivity
      summary: 활동 상태 복원
      description: 화면 복귀·재진입 때 서버 상태를 다시 읽는다. ACTION은 단계 문구, EMPATHY는 공감 메시지를 함께 반환한다(봉인 때 만든 내용, 재생성 없음).
      responses:
        '200':
          content:
            application/json:
              examples:
                action:
                  value:
                    activityId: 5c2d...
                    worryId: 1c25f56a-0b86-41d3-8f8c-2f7c19e7d842
                    helpType: ACTION
                    state: IN_PROGRESS
                    startedAt: '2026-11-12T00:15:00Z'
                    endedAt: null
                    version: 2
                    serverTime: '2026-11-12T00:20:00Z'
                    action:
                      steps:
                      - { stepNo: 1, text: 발표 자료의 첫 장을 열어요., done: true }
                      - { stepNo: 2, text: 첫 문장을 소리 내어 한 번 읽어요., done: false }
                      - { stepNo: 3, text: 막힌 단어 하나를 메모해요., done: true }
                      doneCount: 2
                      canComplete: false
                restPaused:
                  value:
                    activityId: 7a0b...
                    helpType: REST
                    state: PAUSED
                    version: 1
                    serverTime: '2026-11-12T00:17:00Z'
                    rest: { durationSeconds: 180, endsAt: null, remainingSeconds: 95, timeOver: false }
                    restGuide: 발을 바닥에 대고 어깨를 편하게 두어요.

  /activities/{activityId}/steps/{stepNo}:
    put:
      operationId: putActionStep
      summary: 액션 체크 저장·해제 (ACT-02)
      description: stepNo는 1~3. 순서 자유, 해제 가능. 같은 값 재전송은 멱등. IN_PROGRESS 액션만.
      security: [{ guestCookie: [], csrfToken: [] }]
      requestBody:
        content:
          application/json:
            example: { done: true }
      responses:
        '200': { description: getActivity와 같은 본문 }
        '400': { description: INVALID_INPUT (stepNo 범위) }
        '409': { description: INVALID_ACTIVITY_STATE 또는 ACTIVITY_ALREADY_ENDED }

  /activities/{activityId}/pause:
    post:
      operationId: pauseRest
      description: REST·IN_PROGRESS만. 남은 시간을 저장하고 endsAt을 비운다. 이미 PAUSED면 200.
      responses:
        '200': { description: getActivity와 같은 본문 }
        '409': { description: REST_TIME_OVER, INVALID_ACTIVITY_STATE, ACTIVITY_ALREADY_ENDED }

  /activities/{activityId}/resume:
    post:
      operationId: resumeRest
      description: REST·PAUSED만. endsAt = 서버 현재 + remainingSeconds. 이미 IN_PROGRESS면 200.
      responses:
        '200': { description: getActivity와 같은 본문 }
        '409': { description: INVALID_ACTIVITY_STATE, ACTIVITY_ALREADY_ENDED }

  /activities/{activityId}/stop:
    post:
      operationId: stopActivity
      description: 나중에 이어하기(ACTION)·그만두기(REST)·도움 변경. 액션 체크는 유지, 휴식 남은 시간은 버린다. 이미 STOPPED면 200.
      responses:
        '200': { description: getActivity와 같은 본문 (state STOPPED) }
        '409': { description: ACTIVITY_ALREADY_ENDED (이미 완료) }

  /activities/{activityId}/complete:
    post:
      operationId: completeActivity
      description: 사용자의 명시적 완료. 휴식은 시간 종료 후에만, 액션은 3단계 체크 후에만 가능하다. 서버 성공 응답 후 S-13으로 이동한다.
      responses:
        '200':
          content:
            application/json:
              example:
                activityId: 7a0b...
                helpType: REST
                state: COMPLETED
                endedAt: '2026-11-12T00:19:10Z'
                feedback: null
                nextUnviewedExists: true      # RESULT-03 "다음 걱정 보기" 표시 여부
        '409': { description: ACTION_STEPS_INCOMPLETE, REST_NOT_FINISHED, ACTIVITY_ALREADY_ENDED }

  /activities/{activityId}/feedback:
    put:
      operationId: putActivityFeedback
      summary: 도움 평가 (RESULT-02)
      description: 선택 입력. 완료한 활동에만 저장한다. 걱정이 삭제·만료됐으면 404. 평가 실패는 활동 완료 상태에 영향을 주지 않는다.
      requestBody:
        content:
          application/json:
            schema:
              properties:
                answer: { type: string, enum: [HELPFUL, UNSURE, NOT_HELPFUL] }
            example: { answer: HELPFUL }
      responses:
        '200':
          content:
            application/json:
              example: { activityId: 7a0b..., answer: HELPFUL, answeredAt: '2026-11-12T00:19:30Z' }
        '409': { description: ACTIVITY_NOT_COMPLETED }

  /activities/current:
    get:
      operationId: getCurrentActivity
      summary: 홈 이어하기 (HOME-03)
      description: 가장 최근에 상태가 바뀐 진행 중·일시정지 활동 하나. 없으면 204. 원문·요약은 반환하지 않는다(§4-3 홈 카드).
      responses:
        '200':
          content:
            application/json:
              example:
                activityId: 7a0b...
                worryId: 1c25f56a-0b86-41d3-8f8c-2f7c19e7d842
                helpType: REST
                state: PAUSED
                serverTime: '2026-11-12T07:00:00Z'
                progress: { remainingSeconds: 95 }      # ACTION이면 { doneCount: 2, totalSteps: 3 }
        '204': { description: 이어할 활동 없음 }

  /records:
    get:
      operationId: listRecords
      summary: 기록 목록 (R-01)
      parameters:
      - { name: cursor, in: query, required: false, schema: { type: string } }
      - { name: limit, in: query, required: false, schema: { type: integer, minimum: 1, maximum: 100, default: 20 } }
      responses:
        '200':
          content:
            application/json:
              example:
                items:
                - worryId: 1c25f56a-0b86-41d3-8f8c-2f7c19e7d842
                  createdAt: '2026-11-11T14:00:00Z'
                  summary: 발표 첫 문장을 잊을까 걱정했다.
                  summaryEdited: true
                  anxietyBefore: 4
                  anxietyAfter: null              # 화면은 "4 → 미기록", 변화량 계산 안 함
                  latestHelpType: null            # 화면은 "아직 선택하지 않음"
                nextCursor: null

  /records/{worryId}:
    get:
      operationId: getRecord
      summary: 기록 상세 (R-02, R-03)
      description: 매번 소유권·공개·삭제·만료를 검사한다. 열람 기록은 POST /worries/{worryId}/view로 따로 남긴다.
      responses:
        '200':
          content:
            application/json:
              example:
                worryId: 1c25f56a-0b86-41d3-8f8c-2f7c19e7d842
                createdAt: '2026-11-11T14:00:00Z'
                publishAt: '2026-11-11T23:00:00Z'
                firstViewedAt: '2026-11-11T23:01:00Z'
                text: 내일 발표 때 첫 문장을 잊어버릴까 걱정돼.
                memo: 발표 첫 문장 확인
                summary: 발표 첫 문장을 잊을까 걱정했다.
                summaryEdited: true
                anxiety: { before: 4, after: 2, change: -2 }   # 둘 다 있을 때만 change, 아니면 null
                activities:
                - activityId: 5c2d...
                  helpType: ACTION
                  state: STOPPED
                  startedAt: '2026-11-11T23:05:00Z'
                  endedAt: '2026-11-11T23:09:00Z'
                  doneCount: 2
                  resumable: true                 # 중단된 액션 재개 (R-02)
                  feedback: null
                - activityId: 7a0b...
                  helpType: REST
                  state: COMPLETED
                  startedAt: '2026-11-12T00:15:00Z'
                  endedAt: '2026-11-12T00:19:10Z'
                  resumable: false
                  feedback: { answer: HELPFUL, answeredAt: '2026-11-12T00:19:30Z' }
                reflection: { answer: ONGOING, respondedAt: '2026-11-13T00:00:00Z' }

  /worries/{worryId}/reflection-candidate:
    get:
      operationId: getReflectionCandidate
      summary: 회고 후보 (REVIEW-01)
      description: 경로의 worryId는 현재 보고 있는 걱정이며 후보에서 제외된다. 응답의 targetWorryId가 회고 대상이다.
      parameters:
      - { name: timeZone, in: query, required: true, schema: { type: string, maxLength: 100 } }
      responses:
        '200':
          content:
            application/json:
              example:
                targetWorryId: 9e1b...
                createdAt: '2026-11-09T14:00:00Z'
                summary: 면접 결과 연락이 늦을까 걱정했다.
        '204': { description: 후보 없음 또는 오늘 건너뜀 (카드 숨김) }
        '400': { description: INVALID_INPUT (시간대) }

  /worries/{worryId}/reflection:
    put:
      operationId: putReflection
      summary: 회고 응답 (REVIEW-02)
      description: 경로의 worryId는 회고 대상이다. 조건(24시간·열람·미삭제·미만료)을 다시 검사한다. 같은 답 재전송은 200.
      requestBody:
        content:
          application/json:
            schema:
              properties:
                answer: { type: string, enum: [GOT_BETTER, ONGOING, AS_FEARED] }
            example: { answer: GOT_BETTER }
      responses:
        '200':
          content:
            application/json:
              example: { worryId: 9e1b..., answer: GOT_BETTER, respondedAt: '2026-11-12T00:30:00Z' }
        '409': { description: REFLECTION_NOT_AVAILABLE, REFLECTION_ALREADY_ANSWERED }

  /reflection-skips:
    post:
      operationId: skipReflectionToday
      summary: 오늘은 건너뛰기 (REVIEW-03)
      requestBody:
        content:
          application/json:
            example: { timeZone: Asia/Seoul }
      responses:
        '200':
          content:
            application/json:
              example: { localDate: '2026-11-12', timeZone: Asia/Seoul }

  /worries/{worryId}/past-experience:
    get:
      operationId: getPastExperience
      summary: 관련 과거 경험 (PAST-01, PAST-02)
      responses:
        '200':
          content:
            application/json:
              example:
                worryId: 3f7a...
                createdAt: '2026-11-02T13:40:00Z'
                summary: 팀 회의에서 말실수할까 걱정했다.
                helpedBefore: true             # HELPFUL 평가가 있을 때만 true
                helpfulHelpType: ACTION
        '204': { description: 관련 기록 없음 (카드 숨김) }

  /worries/{worryId}:
    delete:
      operationId: deleteWorry               # 기존 openapi 경로. 응답 계약(202 DELETING / 204) 유지
      description: |-
        파쇄·잠긴 걱정 삭제·기록 삭제 공통. 범위는 같다.
        reason 쿼리는 METRIC-01 집계용 제안이다(확인 필요). 없으면 공개 여부로 LOCKED_DELETE/RECORD_DELETE를 정한다.
      parameters:
      - { name: reason, in: query, required: false, schema: { type: string, enum: [SHRED, LOCKED_DELETE, RECORD_DELETE] } }

  /guests/current:
    delete:
      operationId: deleteGuest               # 경로·응답 BE1 합의 필요
      summary: 금고 전체 삭제 (SET-05)
      responses:
        '202': { content: { application/json: { example: { status: DELETING } } } }
        '204': { description: 삭제 완료. 응답에서 인증 쿠키를 만료시킨다. }

  /demo/time-machine:
    post:
      operationId: demoTimeMachine
      summary: 시연 기록 시간 이동 (DEMO-02)
      description: ROLE_DEMO 게스트의 걱정에만 적용한다. 운영 기록·실제 알림에는 영향이 없다.
      requestBody:
        content:
          application/json:
            examples:
              publishNow: { value: { worryId: 1c25..., target: PUBLISH_NOW } }
              reflectionReady: { value: { worryId: 1c25..., target: REFLECTION_READY } }
      responses:
        '200': { description: 갱신된 시연 걱정 메타데이터 }
        '403': { description: DEMO_ONLY }
```

**삭제 API 동작 (H-02, D-01)**

1. 트랜잭션 1: `guests FOR SHARE` → `worries FOR UPDATE` → `deletion_started_at = now`, `deletion_records` INSERT(`PROCESSING`). 커밋 순간부터 모든 조회가 404다.
2. 트랜잭션 2: `DELETE FROM worries WHERE id = :id` → `ai_contents`, `activities`, `activity_feedback`, `reflections`가 CASCADE로 삭제된다. 연결 예약(BE1 알림)을 취소하고 `analysis_requests`를 재실행 불가 상태로 바꾼다(BE1 합의 필요). `deletion_records`를 `COMPLETED`로 바꾼다.
3. 트랜잭션 2가 실패하면 `PROCESSING`으로 남고 202 `DELETING`을 반환한다. 재처리 작업이 `next_attempt_at` 기준으로 다시 실행한다.
4. 같은 DELETE 재요청: 걱정 행이 없으면 `deletion_records`에서 `owner_ref = 현재 게스트`인 행을 찾는다. `COMPLETED`면 204, `PROCESSING`이면 202, 없으면 404.

---

## 5. 기능 ID 매핑

### 5-1. 기능 ID → API·테이블

| 기능 ID | API | 테이블 | 비고 |
| --- | --- | --- | --- |
| A-06 접근 제어 | 전체 | 전체 (`guest_id` 조건) | BE1 필터 공용 |
| HOME-03 활동 이어하기 | #11 | `activities` | |
| HOME-05 상태 재조회 | #4, #11, #13 | `activities`, `worries` | `serverTime` 제공 |
| COM-01 상태·오류·복귀 | 전체 | — | 409·202 구분 |
| METRIC-01 행동·처리 시간 | — | `activities` 시각, `deletion_records.reason` | 내용 없는 집계만 |
| LOCK-02 공개 전 삭제 | #18 | `deletion_records` | reason `LOCKED_DELETE` |
| M-03 현재 불안도 | #2 | `worries.anxiety_after_*` | BE1 합의 필요 |
| M-04 정리 내용 수정 | #1 | `worries.summary_edit_*` | BE1 합의 필요 |
| M-05 도움 선택·변경 | #3 (`replaceOpen`) | `activities` | |
| H-02 공통 삭제 실행 | #18 | `deletion_records` + CASCADE | reason `SHRED` |
| ACT-01 3단계·시작 | #3, #4 | `activities` | |
| ACT-02 체크 저장 | #5 | `activities.step*_done` | |
| ACT-03 중단·재개 | #8, #3 | `activities` | 재개 = 같은 행 |
| ACT-04 전체 완료 | #9 | `activities` | CHECK로도 보장 |
| REST-01 시작 전·시작 | #3 | `activities` | 시작 전은 행 없음 |
| REST-02 일시정지·재개 | #6, #7 | `activities.rest_*` | |
| REST-03 복원·종료 표시 | #4 | `activities.rest_*` | `timeOver` 계산 |
| REST-04 중단 확인·완료 | #8, #9 | `activities` | |
| EMP-02 읽었어요 | #3, #9 | `activities` | |
| RESULT-01 종류별 완료 | #9 | `activities` | 파쇄 제외 |
| RESULT-02 도움 평가 | #10 | `activity_feedback` | |
| RESULT-03 평가 생략·다음 이동 | #9 (`nextUnviewedExists`) | `worries` | |
| R-01 목록 | #12 | `worries`, `ai_contents`, `activities` | |
| R-02 상세·이력 | #13, 기존 view | 전체 | |
| R-03 불안도 비교 | #13 | `worries` | |
| R-04 삭제·빈 상태 | #18, #12 | `deletion_records` | reason `RECORD_DELETE` |
| REVIEW-01 후보 선정 | #14 | `worries`, `reflections`, `reflection_skips` | |
| REVIEW-02 응답 | #15 | `reflections` | |
| REVIEW-03 오늘 건너뛰기 | #16 | `reflection_skips` | |
| PAST-01 관련 검색 | #17 | `worries`, `ai_contents` | 기준 확인 필요 |
| PAST-02 카드·상세 연결 | #17, #13 | `activity_feedback` | |
| D-01 관련 삭제 | #18 | `deletion_records` | |
| D-02 만료·자동 삭제 | (작업) | `worries`, `deletion_records` | reason `EXPIRED` |
| D-03 로그·백업 | — | `deletion_records` (30일) | |
| SET-05 전체 삭제 | #19 | `deletion_records` | BE1 공동 |
| DEMO-01 권한·패널 | #20 | `guests.kind` (BE1 합의 필요) | |
| DEMO-02 타임머신 | #21 | `worries` 시각 | |
| DEMO-03 프리셋·초기화 | #22, #23 | 시연 게스트 데이터 | reason `DEMO_RESET` |

### 5-2. 테이블 → 기능 ID

| 테이블 | 기능 ID |
| --- | --- |
| `worries` 추가 컬럼 | M-03, M-04, R-01, R-03, REVIEW-01, PAST-01 |
| `activities` | HOME-03, M-05, ACT-01~04, REST-01~04, EMP-02, RESULT-01, R-01, R-02 |
| `activity_feedback` | RESULT-02, R-02, PAST-02 |
| `reflections` | REVIEW-01, REVIEW-02, R-02 |
| `reflection_skips` | REVIEW-03 |
| `deletion_records` | H-02, LOCK-02, R-04, D-01, D-02, D-03, SET-05, DEMO-03, METRIC-01 |

---

## 6. 구현 순서

요청받은 순서다. BE2_README의 순서와 다른 점은 §8에 정리했다.

| 순서 | 범위 | 내용 | 완료 기준 | 선행 조건 |
| --- | --- | --- | --- | --- |
| 1 | 공개 후 상세 | 공통 소유권·공개·삭제·만료 검사, `worries` 추가 컬럼 마이그레이션, 요약 수정(#1), 현재 불안도(#2) | 수정본이 원문·AI 원본을 바꾸지 않는다. 공개 전 걱정은 409. 다른 게스트는 404 | BE1: 암호화 모듈 공개, `worries` 컬럼 합의 |
| 2 | 활동 3종 | `activities` 마이그레이션, 시작·재진입·도움 변경(#3), 복원(#4), 액션 체크(#5), 휴식 정지·재개(#6·#7), 중단(#8), 홈 이어하기(#11) | 재전송해도 활동 1건. 휴식 이탈 후 시간이 흐르고 정지 시 유지. 그만두기 후 새 3분 | 1 |
| 3 | 완료·평가 | 완료(#9), `activity_feedback`(#10), `nextUnviewedExists` | 0초 전·3단계 미완료 시 완료 거부. 평가 실패가 완료를 되돌리지 않음 | 2 |
| 4 | 기록 | 기록 목록(#12)·상세(#13), 불안도 비교, 열람 API 공용 | 잠김·삭제·만료 제외, 최신순. 미선택·미기록 구분 | 1~3 |
| 5 | 삭제 | `deletion_records`, 공통 삭제(#18), 재처리 작업, 전체 삭제(#19) | 삭제 시작 즉시 404. 부분 실패 재처리. 반복 삭제는 204 | BE1: 예약 취소·요청 상태 전환 합의 |
| 6 | 시연 | 시연 권한, 상태(#20), 타임머신(#21), 프리셋(#22), 초기화(#23) | 일반 게스트 403. 운영 데이터 무변경 | BE1: `guests.kind`·필터 확장 |
| 7 | 회고·과거 경험 | `reflections`, `reflection_skips`, 후보(#14), 응답(#15), 건너뛰기(#16), 과거 경험(#17) | 현재 걱정 제외. 같은 현지 날짜 재제안 없음. 관련성 부족 시 204 | 관련성 기준 확정 |
| 8 | 만료·로그 | 만료 선점 작업(§3-6), `deletion_records` 30일 정리, `reflection_skips` 정리, 로그 점검 | 만료 직후 정리 전에도 접근 차단. 로그에 원문·AI·토큰 없음 | 5 |

- 단계마다 PostgreSQL 통합 테스트로 소유권(다른 게스트 404)·삭제·만료 케이스를 함께 확인한다(기능 완료 처리 기준).
- 테스트 데이터는 `scripts/dev/create-public-worry.ps1`로 공개된 걱정을 만든다.

---

## 7. BE1과 합의가 필요한 항목

| # | 항목 | BE2 제안 | 관련 기능 |
| --- | --- | --- | --- |
| 1 | `worries` 추가 컬럼 | `summary_edit_ciphertext`, `summary_edited_at`(수정 여부), `anxiety_after_ciphertext`, `anxiety_after_updated_at` | M-03, M-04 |
| 2 | `worries UNIQUE (id, guest_id)` | BE2 테이블의 복합 FK용 | 전체 |
| 3 | 회고 후보 인덱스 | `worries (guest_id, GREATEST(sealed_at, publish_at), id) WHERE deletion_started_at IS NULL` | REVIEW-01 |
| 4 | 마이그레이션 버전 규칙 | 이후 전부 `V{yyyyMMddHHmm}__…`. 섞으면 Flyway 검증 실패 | — |
| 5 | 암호화 모듈 공유 | `AnalysisPayloads`의 암·복호화를 공통 패키지로 분리. 수정 요약 AAD `summary-edit:{worryId}` | M-04, R-02 |
| 6 | 현재 불안도 암호화 여부 | 암호화 제안 (봉인 전 불안도와 같은 수준) | M-03 |
| 7 | 공개 상세 응답 | `GET /worries/{id}`에 `summaryEdited` 추가, `summary`는 수정본 우선. `anxietyAfter` 채움 | M-04, M-03 |
| 8 | `/worries` 상세·열람 구현 주체 | openapi에는 있으나 코드 없음. 상세·열람은 BE1, 삭제는 BE2로 제안 | M-01, M-02, R-02 |
| 9 | 기록 API 분리 | S-14용 `/records`를 BE2가 따로 제공 | R-01, R-02 |
| 10 | 오류 코드 | `ApiError`에 `WORRY_LOCKED` 등 추가. BE2 코드를 같은 enum에 넣을지 결정 | 전체 |
| 11 | 잠금 순서 | 게스트 → (분석 요청) → 걱정 → 활동. 활동 쓰기는 `FOR SHARE`, 삭제는 `FOR UPDATE` | D-01 |
| 12 | 삭제 시 분석 요청 상태 | 걱정 삭제 후 `analysis_requests`를 `CANCELLED`로 바꿔 상태 조회 404·재실행 불가 | D-01 |
| 13 | 알림 예약 취소 연동 | BE2 삭제 트랜잭션 2에서 BE1 예약 취소 메서드 호출 | D-01 |
| 14 | 전체 삭제 | 경로(`DELETE /guests/current` 제안), `guests.state = 'DELETING'` 전환 주체, 인증 쿠키 제거 시점, 진행 상태 확인 방식 | SET-05 |
| 15 | 늦은 AI 결과 폐기 | `finish()`가 이미 게스트 ACTIVE·요청 상태를 검사함. 개별 삭제 시 요청 상태 전환과 맞물리는지 테스트 | SET-05, D-01 |
| 16 | 시연 권한 | `guests.kind` (`REGULAR`/`DEMO`) 추가, 필터에서 `ROLE_DEMO` 부여, 시연 게스트 발급 방법(환경변수로 켜는 별도 엔드포인트 등) | DEMO-01 |
| 17 | 시연 타임머신과 CHECK | `worries_publication_check (publish_at > created_at AND publish_at < expires_at)` 때문에 시각을 옮길 때 `created_at`·`sealed_at`·`publish_at`·`expires_at`을 같은 폭으로 함께 당긴다. 시연 알림은 발송하지 않음 | DEMO-02 |
| 18 | 홈 요약 | 홈 이어하기를 `GET /vault/summary`에 합칠지, BE2 `GET /activities/current`를 따로 호출할지 | HOME-03 |
| 19 | 만료 계산 | 30×24시간(ERD) 유지 확인 | D-02 |

---

## 8. 문서 간 규칙 차이

| # | 주제 | 문서 A | 문서 B | 이 설계의 처리 |
| --- | --- | --- | --- | --- |
| 1 | "시작 전"과 "나중에 하기" | 기능명세 §2 상태표: 시작 전 상태가 있고 "중단"에 나중에 하기 포함 | 화면 정책 §7-3: 휴식 "다음에 할게요"는 **활동을 만들지 않음** | 시작 전은 DB 행 없음. "다음에 할게요"는 호출 없음. 액션 "나중에 이어하기"만 중단(`STOPPED`). **확인 필요** |
| 2 | 중단 활동 재개 범위 | HOME-03: "중단 활동은 기록에서 재개" (유형 구분 없음) | §7-3·REST-04: 휴식 그만두기 후 다시 시작하면 새 3분. R-02: "중단된 **액션** 재개" | 재개는 액션만. 휴식·공감은 새 행 |
| 3 | 요약 수정 글자 수 | M-04: "공백 제외 1자~500자" | §3·§6-4: 공백 제외 최소 1자, **공백 포함** 최대 500자 | 원문과 같은 기준(`WorryText`) 사용: 공백 제외 1자 이상, 공백 포함 500 코드 포인트 이하 |
| 4 | 파쇄 선택 시 기존 활동 | M-05·§6-5: 변경 확인 시 기존 활동 중단 후 별도 삭제 확인 | (삭제 확인 취소 시 처리 규칙 없음) | 파쇄는 활동 중단 호출 없이 삭제만 수행. **확인 필요** |
| 5 | 수정 출처 표시 | M-04: 출처 '내가 정리했어요', 수정 여부 저장 | openapi `PublicWorry`: 수정 여부 필드 없음 | `summaryEdited` 추가 제안. BE1 합의 필요 |
| 6 | 현재 불안도 담당 | BE2_rules·README: M-03은 BE2 | openapi `GET /worries/{id}`(BE1)에 `anxietyAfter` 포함, ERD는 `worries`에 컬럼 미정 | 저장 API는 BE2, 조회 필드는 BE1 응답에 포함. BE1 합의 필요 |
| 7 | 활동 테이블 컬럼 | ERD/DBML: `id, worry_id, help_type, state`만 | BE2_rules §3: 시작·종료 시각, 체크 3개, 남은 시간, 마지막 상태 변경 시각 | BE2_rules 기준으로 확장. ERD는 BE1과 합의 후 갱신 |
| 8 | 회고 테이블 | ERD: `reflections(worry_id, answer, responded_at)`, 건너뛰기 테이블 없음 | REVIEW-03: 현지 날짜·시간대 저장 | `guest_id` 추가, `reflection_skips` 신설 |
| 9 | 삭제 이력 컬럼 | ERD: `owner_ref`, `target_type`, `target_id`, `state`, `completed_at` | D-01 부분 실패 재처리, METRIC-01 집계 | 재시도·사유·정리 시각 컬럼 추가 |
| 10 | 삭제 사유 구분 | openapi `DELETE /worries/{id}`: 파쇄·일반 삭제 구분 없음 | METRIC-01: 활동 집계 | `reason` 쿼리 파라미터 제안. **확인 필요** |
| 11 | 마이그레이션 버전 | 기존 V1·V2 정수 버전 | 이번 요청: 날짜 버전 | 날짜 버전 사용. BE1 합의 필요 |
| 12 | 구현 순서 | BE2_README: 상세 → 활동 → 액션·휴식·공감 → 완료·평가 → **회고·과거 경험 → 삭제·만료 → 시연** | 이번 요청: … → 기록 → **삭제 → 시연 → 회고·과거 경험 → 만료·로그** | 요청 순서 사용(§6). README 갱신 여부 **확인 필요** |
| 13 | 시연 데이터 구분 | BE2_rules §3: "시연 데이터 구분 컬럼" | ERD·V1에 없음 | 게스트 단위(`guests.kind`)로 구분 제안. 걱정 단위 컬럼은 두지 않음. **확인 필요** |
| 14 | 만료 단위 | 정책: "30일" | ERD: 30×24시간 | ERD 따름 (BE1 코드와 일치 여부 확인 필요) |

---

## 9. 그 밖의 확인 필요 목록

- 공감(EMPATHY)을 S-12 진입 시 `IN_PROGRESS`로 만들지, "읽었어요" 때 바로 `COMPLETED`로 만들지. 진입 시 생성하면 "최신 선택 도움"에 남지만, 읽지 않고 나가면 홈 이어하기에 공감이 뜬다.
- 완료한 액션을 다시 고를 때 새 행(체크 초기화)으로 시작하는지.
- 평가 변경 허용 여부(초안: 덮어쓰기 허용, `updated_at` 갱신).
- 회고 재응답 정책(초안: 같은 답은 200, 다른 답은 409).
- 과거 경험의 관련성 기준·임계값·후보 상한.
- 시연 권한 부여 방식, 시연 휴식 시간 단축 여부, 프리셋 목록.
- 시연 권한 없음 응답을 403으로 할지 404(존재 숨김)로 할지.
- 백업 운영 여부(D-03 "백업 운영 시 최대 7일·복원 시 삭제 재적용"). 복원 시 `deletion_records`(30일)로 삭제를 다시 적용하므로 이력 보관 30일 ≥ 백업 7일 조건이 맞다.
- METRIC-01 집계 저장 위치(별도 테이블 없이 활동·삭제 이력 시각으로 계산하는 안).
