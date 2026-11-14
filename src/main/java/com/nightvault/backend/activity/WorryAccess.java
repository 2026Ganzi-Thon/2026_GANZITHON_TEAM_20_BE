package com.nightvault.backend.activity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 걱정 접근 검사(소유권·공개 여부·삭제·만료)와 활동에 필요한 걱정 조회를 한곳에 모은 클래스.
 *
 * <p>BE1 공용 걱정 모듈이 생기면 이 클래스만 교체한다. 판정 기준은 BE2 설계 §3 공통 조건과 같다.</p>
 * <ul>
 *   <li>다른 게스트·게스트 삭제 중·걱정 삭제 중·만료 → 404 {@code NOT_FOUND} (존재를 드러내지 않음)</li>
 *   <li>공개 전({@code publish_at > now}) → 409 {@code WORRY_LOCKED}</li>
 * </ul>
 * <p>시각은 DB가 아니라 호출자가 넘긴 {@code now}(서버 {@code Clock})로 판정한다.</p>
 */
@Component
class WorryAccess {
    /**
     * 다른 테이블 조회에서 걱정(별칭 {@code w})을 거를 때 쓰는 조건: 공개됨·미삭제·미만료. {@code :now} 파라미터가 필요하다.
     * 소유자 조건({@code guest_id})은 호출하는 쿼리가 넣는다.
     */
    static final String VISIBLE_WORRY = "w.deletion_started_at IS NULL AND w.expires_at > :now AND w.publish_at <= :now";

    private final JdbcClient jdbc;

    WorryAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 활동 쓰기 전에 호출한다. 게스트 → 걱정 순서로 {@code FOR SHARE} 잠금을 잡아
     * 같은 트랜잭션 동안 삭제(걱정 {@code FOR UPDATE})가 끼어들지 못하게 한다.
     */
    void lockForWrite(UUID guestId, UUID worryId, Instant now) {
        boolean guestActive = jdbc.sql("""
                SELECT 1 FROM guests WHERE id = :guestId AND state = 'ACTIVE' FOR SHARE
                """)
            .param("guestId", guestId)
            .query(Integer.class).optional().isPresent();
        if (!guestActive) {
            throw new ActivityException(ApiError.NOT_FOUND);
        }
        require(jdbc.sql("""
                SELECT publish_at <= :now
                FROM worries
                WHERE id = :worryId AND guest_id = :guestId
                  AND deletion_started_at IS NULL AND expires_at > :now
                FOR SHARE
                """)
            .param("worryId", worryId)
            .param("guestId", guestId)
            .param("now", utc(now))
            .query(Boolean.class).optional());
    }

    /** 읽기 전용 검사. 잠금을 잡지 않는다. */
    void check(UUID guestId, UUID worryId, Instant now) {
        require(jdbc.sql("""
                SELECT w.publish_at <= :now
                FROM worries w
                JOIN guests g ON g.id = w.guest_id AND g.state = 'ACTIVE'
                WHERE w.id = :worryId AND w.guest_id = :guestId
                  AND w.deletion_started_at IS NULL AND w.expires_at > :now
                """)
            .param("worryId", worryId)
            .param("guestId", guestId)
            .param("now", utc(now))
            .query(Boolean.class).optional());
    }

    /** RESULT-03: 현재 걱정 말고 아직 열어 보지 않은 공개 걱정이 있는지. */
    boolean otherUnviewedExists(UUID guestId, UUID currentWorryId, Instant now) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM worries w
                    WHERE w.guest_id = :guestId AND w.id <> :worryId
                      AND w.deletion_started_at IS NULL AND w.expires_at > :now
                      AND w.publish_at <= :now
                      AND w.first_viewed_at IS NULL
                )
                """)
            .param("guestId", guestId)
            .param("worryId", currentWorryId)
            .param("now", utc(now))
            .query(Boolean.class).single();
    }

    private static void require(Optional<Boolean> published) {
        if (published.isEmpty()) {
            throw new ActivityException(ApiError.NOT_FOUND);
        }
        if (!published.get()) {
            throw new ActivityException(ApiError.WORRY_LOCKED);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
