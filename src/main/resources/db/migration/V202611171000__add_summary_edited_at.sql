-- M-04: 요약 수정 여부를 저장한다. NULL이 아니면 사용자가 수정한 요약이다.
ALTER TABLE worries ADD COLUMN summary_edited_at timestamptz;

-- 이전에 수정된 행은 실제 수정 시각을 알 수 없으므로 수정이 가능해진 최초 시각으로 채운다.
UPDATE worries SET summary_edited_at = GREATEST(sealed_at, publish_at)
WHERE edited_summary_ciphertext IS NOT NULL;

ALTER TABLE worries ADD CONSTRAINT worries_summary_edit_check
    CHECK ((edited_summary_ciphertext IS NULL) = (summary_edited_at IS NULL));
