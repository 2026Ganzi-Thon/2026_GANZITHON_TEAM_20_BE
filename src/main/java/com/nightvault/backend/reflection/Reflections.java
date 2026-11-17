package com.nightvault.backend.reflection;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import com.nightvault.backend.worry.WorryAccess;
import com.nightvault.backend.guest.GuestWriteLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Reflections {
    private final JdbcClient jdbc;
    private final GuestWriteLock guestWriteLock;
    private final WorryAccess access;
    private final Clock clock;

    Reflections(JdbcClient jdbc, WorryAccess access, Clock clock, GuestWriteLock guestWriteLock) {
        this.jdbc = jdbc;
        this.guestWriteLock = guestWriteLock;
        this.access = access;
        this.clock = clock;
    }

    Candidate candidate(UUID guestId, UUID currentId, ZoneId zone) {
        var now = OffsetDateTime.now(clock);
        return jdbc.sql("""
            SELECT w.id, GREATEST(w.sealed_at, w.publish_at) AS first_available_at
            FROM worries w JOIN guests g ON g.id=w.guest_id AND g.state='ACTIVE'
            WHERE w.guest_id=:guest AND w.id<>:current AND w.first_viewed_at IS NOT NULL
              AND w.deletion_started_at IS NULL AND w.expires_at>:now
              AND GREATEST(w.sealed_at, w.publish_at)<=:cutoff
              AND NOT EXISTS (SELECT 1 FROM reflections r WHERE r.worry_id=w.id)
              AND NOT EXISTS (SELECT 1 FROM reflection_skips s WHERE s.guest_id=:guest AND s.local_date=:date)
            ORDER BY first_available_at, w.id LIMIT 1
            """).param("guest", guestId).param("current", currentId).param("now", now)
            .param("cutoff", now.minusHours(24)).param("date", now.atZoneSameInstant(zone).toLocalDate())
            .query((row, index) -> new Candidate(row.getObject("id", UUID.class),
                row.getObject("first_available_at", OffsetDateTime.class))).optional().orElse(null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Response read(UUID guestId, UUID worryId) {
        access.requirePublic(guestId, worryId);
        return jdbc.sql("SELECT answer,responded_at FROM reflections WHERE worry_id=:id")
            .param("id", worryId).query((row, index) -> new Response(Answer.valueOf(row.getString("answer")),
                row.getObject("responded_at", OffsetDateTime.class))).optional().orElse(null);
    }

    @Transactional
    public void skip(UUID guestId, ZoneId zone) {
        guestWriteLock.requireActive(guestId, ApiError.NOT_FOUND);
        var now = OffsetDateTime.now(clock);
        // 같은 현지 날짜에 다시 누르면 처음 저장한 시간대·시각을 유지한다.
        jdbc.sql("""
            INSERT INTO reflection_skips (guest_id, local_date, time_zone, skipped_at)
            VALUES (:guest, :date, :zone, :now) ON CONFLICT (guest_id, local_date) DO NOTHING
            """).param("guest", guestId).param("date", now.atZoneSameInstant(zone).toLocalDate())
            .param("zone", zone.getId()).param("now", now).update();
    }

    /**
     * 회고 응답 (REVIEW-02). 같은 답을 다시 보내면 저장 없이 성공한다(재전송·중복 탭).
     * 이미 다른 답을 했으면 반복 응답을 받지 않으므로 409 {@code REFLECTION_ALREADY_ANSWERED}다.
     */
    @Transactional
    public void answer(UUID guestId, UUID worryId, UUID currentId, ZoneId zone, Answer answer) {
        access.lockPublic(guestId, worryId);
        var existing = jdbc.sql("SELECT answer FROM reflections WHERE worry_id=:id").param("id", worryId)
            .query(String.class).optional();
        if (existing.isPresent()) {
            if (existing.get().equals(answer.name())) {
                return;
            }
            throw new BusinessException(ApiError.REFLECTION_ALREADY_ANSWERED);
        }
        var candidate = candidate(guestId, currentId, zone);
        if (candidate == null || !candidate.worryId().equals(worryId)) {
            throw new BusinessException(ApiError.REFLECTION_UNAVAILABLE);
        }
        jdbc.sql("INSERT INTO reflections (worry_id,answer,responded_at) VALUES (:id,:answer,:now)")
            .param("id", worryId).param("answer", answer.name()).param("now", OffsetDateTime.now(clock)).update();
    }

    enum Answer { BETTER, ONGOING, AS_FEARED }
    record Candidate(UUID worryId, OffsetDateTime firstAvailableAt) {}
    record Response(Answer answer, OffsetDateTime respondedAt) {}
}
