package com.nightvault.backend.activity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import com.nightvault.backend.worry.HelpType;
import com.nightvault.backend.worry.WorryAccess;
import com.nightvault.backend.worry.WorryActivityHistory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
class Activities implements WorryActivityHistory {
    private static final int REST_MS = 180_000;
    private static final String SELECT = """
        SELECT a.*, f.answer FROM activities a LEFT JOIN activity_feedback f ON f.activity_id = a.id
        WHERE a.worry_id = :worryId
        """;

    private final JdbcClient jdbc;
    private final WorryAccess access;
    private final Clock clock;

    Activities(JdbcClient jdbc, WorryAccess access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<View> list(UUID guest, UUID worry) {
        access.requirePublic(guest, worry);
        var now = OffsetDateTime.now(clock);
        return query(worry, " ORDER BY a.created_at DESC, a.id DESC")
            .query(Activities::read).list().stream().map(activity -> activity.view(now)).toList();
    }

    View latestActive(UUID guest) {
        var now = OffsetDateTime.now(clock);
        return jdbc.sql("""
            SELECT a.*, NULL::text AS answer FROM activities a
            JOIN worries w ON w.id=a.worry_id
            JOIN guests g ON g.id=w.guest_id AND g.state='ACTIVE'
            WHERE w.guest_id=:guest AND w.publish_at<=:now AND w.expires_at>:now
              AND w.deletion_started_at IS NULL AND a.state IN ('IN_PROGRESS','PAUSED')
            ORDER BY a.updated_at DESC, a.id DESC LIMIT 1
            """).param("guest", guest).param("now", now).query(Activities::read)
            .optional().map(row -> row.view(now)).orElse(null);
    }

    @Override
    public Map<UUID, Latest> latestFor(UUID guestId, List<UUID> worryIds) {
        if (worryIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
            SELECT DISTINCT ON (a.worry_id) a.worry_id,a.help_type,f.answer
            FROM activities a JOIN worries w ON w.id=a.worry_id
            JOIN guests g ON g.id=w.guest_id AND g.state='ACTIVE'
            LEFT JOIN activity_feedback f ON f.activity_id=a.id
            WHERE w.guest_id=:guest AND w.id IN (:ids) AND w.publish_at<=:now AND w.expires_at>:now
              AND w.deletion_started_at IS NULL
            ORDER BY a.worry_id,a.created_at DESC,a.id DESC
            """).param("guest", guestId).param("ids", worryIds).param("now", OffsetDateTime.now(clock))
            .query((row, index) -> Map.entry(row.getObject("worry_id", UUID.class),
                new Latest(HelpType.valueOf(row.getString("help_type")), "HELPFUL".equals(row.getString("answer")))))
            .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Transactional
    public View start(UUID guest, UUID worry, UUID key, HelpType type, boolean replaceActive) {
        if (type == HelpType.SHRED) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        access.lockPublic(guest, worry);
        var now = OffsetDateTime.now(clock);
        var previous = query(worry, " AND a.start_key = :key").param("key", key).query(Activities::read).optional();
        if (previous.isPresent()) {
            if (previous.get().type() != type) {
                throw new BusinessException(ApiError.IDEMPOTENCY_CONFLICT);
            }
            return previous.get().view(now);
        }
        var active = query(worry, " AND a.state IN ('IN_PROGRESS', 'PAUSED')").query(Activities::read).optional();
        if (active.isPresent()) {
            var value = active.get();
            if (!replaceActive) {
                throw conflict();
            }
            save(value, new Change(State.STOPPED, value.steps(), null, null), now);
        }
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO activities (id, worry_id, start_key, help_type, state, ends_at, created_at, updated_at)
            VALUES (:id, :worry, :key, :type, 'IN_PROGRESS', :ends, :now, :now)
            """).param("id", id).param("worry", worry).param("key", key).param("type", type.name())
            .param("ends", type == HelpType.REST ? now.plusSeconds(180) : null).param("now", now).update();
        return find(worry, id).view(now);
    }

    @Transactional
    public View action(UUID guest, UUID worry, UUID id, Command command, Integer step, Boolean checked) {
        access.lockPublic(guest, worry);
        var current = find(worry, id);
        var now = OffsetDateTime.now(clock);
        if (command == Command.CHECK) {
            if (step == null || step < 1 || step > 3 || checked == null) {
                throw new BusinessException(ApiError.INVALID_INPUT);
            }
        } else if (step != null || checked != null) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        if ((command == Command.COMPLETE && current.state() == State.COMPLETED)
            || (command == Command.STOP && current.state() == State.STOPPED)
            || (command == Command.PAUSE && current.state() == State.PAUSED)
            || (command == Command.RESUME && current.state() == State.IN_PROGRESS
                && (current.type() == HelpType.ACTION || current.type() == HelpType.REST))) {
            return current.view(now);
        }
        Change next = switch (command) {
            case CHECK -> {
                require(current.type() == HelpType.ACTION && current.state() == State.IN_PROGRESS);
                int mask = 1 << (step - 1);
                yield new Change(current.state(), checked ? current.steps() | mask : current.steps() & ~mask, null, null);
            }
            case PAUSE -> {
                require(current.type() == HelpType.REST && current.state() == State.IN_PROGRESS && current.remaining(now) > 0);
                yield new Change(State.PAUSED, 0, null, current.remaining(now));
            }
            case RESUME -> {
                if (current.type() == HelpType.REST) {
                    require(current.state() == State.PAUSED);
                    yield new Change(State.IN_PROGRESS, 0, now.plus(Duration.ofMillis(current.remaining(now))), null);
                }
                require(current.type() == HelpType.ACTION && current.state() == State.STOPPED);
                require(query(worry, " AND a.state IN ('IN_PROGRESS', 'PAUSED')").query(Activities::read).optional().isEmpty());
                yield new Change(State.IN_PROGRESS, current.steps(), null, null);
            }
            case STOP -> {
                require(current.state() == State.IN_PROGRESS || current.state() == State.PAUSED);
                yield new Change(State.STOPPED, current.steps(), null, null);
            }
            case COMPLETE -> {
                require(current.state() == State.IN_PROGRESS);
                require(switch (current.type()) {
                    case ACTION -> current.steps() == 7;
                    case REST -> current.remaining(now) == 0;
                    case EMPATHY -> true;
                    case SHRED -> false;
                });
                yield new Change(State.COMPLETED, current.steps(), null, null);
            }
        };
        save(current, next, now);
        return find(worry, id).view(now);
    }

    @Transactional
    public View feedback(UUID guest, UUID worry, UUID id, Feedback feedback) {
        access.lockPublic(guest, worry);
        var activity = find(worry, id);
        require(activity.state() == State.COMPLETED);
        jdbc.sql("""
            INSERT INTO activity_feedback (activity_id, answer) VALUES (:id, :answer)
            ON CONFLICT (activity_id) DO UPDATE SET answer = EXCLUDED.answer
            """).param("id", id).param("answer", feedback.name()).update();
        return find(worry, id).view(OffsetDateTime.now(clock));
    }

    private JdbcClient.StatementSpec query(UUID worryId, String condition) {
        return jdbc.sql(SELECT + condition).param("worryId", worryId);
    }

    private Row find(UUID worryId, UUID activityId) {
        return query(worryId, " AND a.id = :id").param("id", activityId)
            .query(Activities::read).optional().orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND));
    }

    private void save(Row activity, Change change, OffsetDateTime now) {
        jdbc.sql("""
            UPDATE activities SET state = :state, checked_steps = :steps, ends_at = :ends,
                remaining_ms = :remaining, updated_at = :now WHERE id = :id
            """).param("id", activity.id()).param("state", change.state().name())
            .param("steps", change.steps()).param("ends", change.endsAt())
            .param("remaining", change.remainingMs()).param("now", now).update();
    }

    private static Row read(ResultSet row, int index) throws SQLException {
        return new Row(row.getObject("id", UUID.class), row.getObject("worry_id", UUID.class),
            HelpType.valueOf(row.getString("help_type")), State.valueOf(row.getString("state")),
            row.getInt("checked_steps"), row.getObject("ends_at", OffsetDateTime.class),
            row.getObject("remaining_ms", Integer.class), row.getString("answer"));
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw conflict();
        }
    }

    private static BusinessException conflict() {
        return new BusinessException(ApiError.ACTIVITY_CONFLICT);
    }

    enum State { IN_PROGRESS, PAUSED, STOPPED, COMPLETED }
    enum Command { CHECK, PAUSE, RESUME, STOP, COMPLETE }
    enum Feedback { HELPFUL, UNSURE, NOT_HELPFUL }

    record View(UUID activityId, UUID worryId, HelpType helpType, State state, List<Integer> checkedSteps,
                Integer remainingMillis, OffsetDateTime endsAt, String feedback) {}
    private record Change(State state, int steps, OffsetDateTime endsAt, Integer remainingMs) {}
    private record Row(UUID id, UUID worryId, HelpType type, State state, int steps,
                       OffsetDateTime endsAt, Integer remainingMs, String feedback) {
        int remaining(OffsetDateTime now) {
            if (endsAt != null) {
                long nanos = Duration.between(now, endsAt).toNanos();
                return (int) Math.clamp(Math.ceilDiv(nanos, 1_000_000), 0, REST_MS);
            }
            return remainingMs == null ? 0 : remainingMs;
        }

        View view(OffsetDateTime now) {
            return new View(id, worryId, type, state,
                IntStream.rangeClosed(1, 3).filter(step -> (steps & (1 << (step - 1))) != 0).boxed().toList(),
                type == HelpType.REST ? remaining(now) : null, endsAt, feedback);
        }
    }
}
