package com.nightvault.backend.activity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.nightvault.backend.activity.ActivityRow.ActivityState;
import com.nightvault.backend.activity.ActivityRow.HelpType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code activities} SQL. 잠금 순서(게스트 → 걱정 → 활동)는 호출하는 서비스가 지킨다. */
@Repository
class ActivityStore {
    private static final String COLUMNS = """
        id, guest_id, worry_id, help_type, state, started_at, last_started_at, state_changed_at, ended_at,
        step1_done, step2_done, step3_done, rest_duration_seconds, rest_ends_at, rest_remaining_seconds, version
        """;

    private final JdbcClient jdbc;

    ActivityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 소유자 확인 겸 걱정 ID 조회. 잠금 없음. */
    Optional<UUID> findWorryId(UUID guestId, UUID activityId) {
        return jdbc.sql("SELECT worry_id FROM activities WHERE id = :id AND guest_id = :guestId")
            .param("id", activityId).param("guestId", guestId)
            .query(UUID.class).optional();
    }

    Optional<ActivityRow> find(UUID guestId, UUID activityId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM activities WHERE id = :id AND guest_id = :guestId")
            .param("id", activityId).param("guestId", guestId)
            .query(ActivityStore::map).optional();
    }

    Optional<ActivityRow> lock(UUID guestId, UUID activityId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM activities WHERE id = :id AND guest_id = :guestId FOR UPDATE")
            .param("id", activityId).param("guestId", guestId)
            .query(ActivityStore::map).optional();
    }

    /** 같은 걱정의 열린(진행 중·일시정지) 활동. 부분 유일 인덱스로 최대 하나다. */
    Optional<ActivityRow> lockOpen(UUID worryId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM activities WHERE worry_id = :worryId AND state IN ('IN_PROGRESS', 'PAUSED')
                FOR UPDATE
                """)
            .param("worryId", worryId)
            .query(ActivityStore::map).optional();
    }

    /** 같은 걱정의 중단된 액션. 부분 유일 인덱스로 최대 하나다. */
    Optional<ActivityRow> lockStoppedAction(UUID worryId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM activities WHERE worry_id = :worryId AND help_type = 'ACTION' AND state = 'STOPPED'
                FOR UPDATE
                """)
            .param("worryId", worryId)
            .query(ActivityStore::map).optional();
    }

    /**
     * 새 활동을 {@code IN_PROGRESS}로 만든다. 동시에 다른 요청이 같은 걱정에 열린 활동을 만들었으면
     * 유일 인덱스 충돌을 {@code DO NOTHING}으로 넘기고 빈 값을 돌려준다(호출자가 다시 읽는다).
     */
    Optional<ActivityRow> insert(UUID guestId, UUID worryId, HelpType helpType, Instant now, Integer restDurationSeconds) {
        boolean action = helpType == HelpType.ACTION;
        boolean rest = helpType == HelpType.REST;
        return jdbc.sql("""
                INSERT INTO activities (guest_id, worry_id, help_type, state, started_at, last_started_at, state_changed_at,
                                        step1_done, step2_done, step3_done, rest_duration_seconds, rest_ends_at)
                VALUES (:guestId, :worryId, :helpType, 'IN_PROGRESS', :now, :now, :now,
                        :step, :step, :step, :restDuration, :restEndsAt)
                ON CONFLICT DO NOTHING
                RETURNING
                """ + COLUMNS)
            .param("guestId", guestId)
            .param("worryId", worryId)
            .param("helpType", helpType.name())
            .param("now", utc(now))
            .param("step", action ? Boolean.FALSE : null, Types.BOOLEAN)
            .param("restDuration", rest ? restDurationSeconds : null, Types.INTEGER)
            .param("restEndsAt", rest ? utc(now.plusSeconds(restDurationSeconds)) : null, Types.TIMESTAMP_WITH_TIMEZONE)
            .query(ActivityStore::map).optional();
    }

    void update(ActivityRow row) {
        jdbc.sql("""
                UPDATE activities
                SET state = :state, last_started_at = :lastStartedAt, state_changed_at = :stateChangedAt,
                    ended_at = :endedAt, step1_done = :step1, step2_done = :step2, step3_done = :step3,
                    rest_ends_at = :restEndsAt, rest_remaining_seconds = :restRemaining, version = :version
                WHERE id = :id
                """)
            .param("id", row.id())
            .param("state", row.state().name())
            .param("lastStartedAt", utc(row.lastStartedAt()))
            .param("stateChangedAt", utc(row.stateChangedAt()))
            .param("endedAt", utc(row.endedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("step1", row.step1Done(), Types.BOOLEAN)
            .param("step2", row.step2Done(), Types.BOOLEAN)
            .param("step3", row.step3Done(), Types.BOOLEAN)
            .param("restEndsAt", utc(row.restEndsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("restRemaining", row.restRemainingSeconds(), Types.INTEGER)
            .param("version", row.version())
            .update();
    }

    private static ActivityRow map(ResultSet row, int rowNumber) throws SQLException {
        return new ActivityRow(
            row.getObject("id", UUID.class),
            row.getObject("guest_id", UUID.class),
            row.getObject("worry_id", UUID.class),
            HelpType.valueOf(row.getString("help_type")),
            ActivityState.valueOf(row.getString("state")),
            instant(row, "started_at"),
            instant(row, "last_started_at"),
            instant(row, "state_changed_at"),
            instant(row, "ended_at"),
            row.getObject("step1_done", Boolean.class),
            row.getObject("step2_done", Boolean.class),
            row.getObject("step3_done", Boolean.class),
            row.getObject("rest_duration_seconds", Integer.class),
            instant(row, "rest_ends_at"),
            row.getObject("rest_remaining_seconds", Integer.class),
            row.getInt("version"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
