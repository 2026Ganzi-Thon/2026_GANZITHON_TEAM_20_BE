-- 시연 게스트 구분(DEMO-01). 설계: docs/roles/BE2_design.md §16
-- 시연 데이터는 게스트 단위로 구분한다. 걱정·활동 행은 guest_id로 시연 여부를 따른다.
ALTER TABLE guests
    ADD COLUMN kind text NOT NULL DEFAULT 'REGULAR',
    ADD CONSTRAINT guests_kind_check CHECK (kind IN ('REGULAR', 'DEMO'));
