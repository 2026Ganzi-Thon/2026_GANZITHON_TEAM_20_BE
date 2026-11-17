-- REVIEW-03: 오늘은 건너뛰기를 누른 현지 날짜와 함께 그때의 시간대와 서버 시각을 저장한다.
ALTER TABLE reflection_skips
    ADD COLUMN time_zone text,
    ADD COLUMN skipped_at timestamptz;

-- 이전 행은 시간대를 알 수 없다. 정리 작업이 이틀 뒤 지우므로 값만 채운다.
UPDATE reflection_skips SET time_zone = 'UTC', skipped_at = local_date::timestamp AT TIME ZONE 'UTC';

ALTER TABLE reflection_skips
    ALTER COLUMN time_zone SET NOT NULL,
    ALTER COLUMN skipped_at SET NOT NULL,
    ADD CONSTRAINT reflection_skips_time_zone_check CHECK (char_length(time_zone) BETWEEN 1 AND 100);
