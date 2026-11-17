package com.nightvault.backend.deletion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import com.nightvault.backend.guest.GuestWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class VaultDeletion {
    private static final Logger LOG = LoggerFactory.getLogger(VaultDeletion.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private final JdbcClient jdbc;
    private final GuestWriteLock guestWriteLock;
    private final Clock clock;
    private final TransactionTemplate transaction;

    VaultDeletion(JdbcClient jdbc, Clock clock, TransactionTemplate transaction, GuestWriteLock guestWriteLock) {
        this.jdbc = jdbc;
        this.guestWriteLock = guestWriteLock;
        this.clock = clock;
        this.transaction = transaction;
    }

    /**
     * 개별 삭제 (H-02, D-01). 삭제 시작을 먼저 커밋해 조회를 막고, 같은 요청에서 정리까지 시도한다.
     * 정리가 끝나면 true(204), 정리가 실패하면 false(202)이며 남은 정리는 스케줄러가 재처리한다.
     * 만료됐지만 아직 정리 전인 걱정도 같은 방식으로 정리한 뒤 완료로 응답한다.
     */
    boolean deleteWorry(UUID guestId, UUID worryId) {
        boolean started = Boolean.TRUE.equals(transaction.execute(status -> {
            guestWriteLock.requireActive(guestId, ApiError.NOT_FOUND);
            var now = OffsetDateTime.now(clock);
            if (jdbc.sql("""
                UPDATE worries SET deletion_started_at = COALESCE(deletion_started_at, :now)
                WHERE id = :id AND guest_id = :guest
                """).param("id", worryId).param("guest", guestId).param("now", now).update() > 0) {
                return true;
            }
            if (jdbc.sql("""
                SELECT 1 FROM worry_deletion_receipts WHERE worry_id = :id AND guest_id = :guest AND completed_at > :cutoff
                """).param("id", worryId).param("guest", guestId).param("cutoff", now.minusDays(30))
                .query(Integer.class).optional().isPresent()) {
                return false;
            }
            throw new BusinessException(ApiError.NOT_FOUND);
        }));
        return !started || tryFinish(new Candidate(worryId, guestId, null));
    }

    void deleteVault(UUID guestId) {
        transaction.executeWithoutResult(status -> {
            guestWriteLock.requireActive(guestId, ApiError.NOT_FOUND);
            jdbc.sql("DELETE FROM guests WHERE id = :id").param("id", guestId).update();
        });
    }

    /**
     * 만료·삭제 시작 걱정 정리 (D-01·D-02). 정리하지 못한 행이 앞에 쌓여도 뒤 행이 막히지 않게
     * (만료 시각, ID) 순서로 페이지를 넘기며 한 번에 최대 {@code PAGE_SIZE * MAX_PAGES}건을 시도한다.
     * 활성 게스트가 아닌 행은 전체 삭제가 지우므로 대상에서 뺀다.
     */
    @Scheduled(fixedDelay = 5000)
    void cleanUp() {
        var now = OffsetDateTime.now(clock);
        Candidate after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            var candidates = candidates(now, after);
            candidates.forEach(this::tryFinish);
            if (candidates.size() < PAGE_SIZE) {
                break;
            }
            after = candidates.getLast();
        }
        jdbc.sql("DELETE FROM worry_deletion_receipts WHERE completed_at <= :cutoff")
            .param("cutoff", now.minusDays(30)).update();
        jdbc.sql("DELETE FROM reflection_skips WHERE local_date < :cutoff").param("cutoff", now.toLocalDate().minusDays(2)).update();
        jdbc.sql("DELETE FROM analysis_requests WHERE expires_at <= :now AND NOT EXISTS (SELECT 1 FROM worries WHERE request_id = analysis_requests.id)")
            .param("now", now).update();
    }

    private List<Candidate> candidates(OffsetDateTime now, Candidate after) {
        String position = after == null ? "" : " AND (w.expires_at, w.id) > (:afterTime, :afterId)";
        var query = jdbc.sql("""
            SELECT w.id, w.guest_id, w.expires_at FROM worries w
            JOIN guests g ON g.id = w.guest_id AND g.state = 'ACTIVE'
            WHERE (w.expires_at <= :now OR w.deletion_started_at IS NOT NULL)
            """ + position + " ORDER BY w.expires_at, w.id LIMIT :limit")
            .param("now", now).param("limit", PAGE_SIZE);
        if (after != null) {
            query.param("afterTime", after.expiresAt()).param("afterId", after.worryId());
        }
        return query.query((row, index) -> new Candidate(row.getObject("id", UUID.class),
            row.getObject("guest_id", UUID.class), row.getObject("expires_at", OffsetDateTime.class))).list();
    }

    private boolean tryFinish(Candidate candidate) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> finish(candidate)));
        } catch (RuntimeException exception) {
            // 예외 메시지에 값이 섞일 수 있어 종류만 남긴다(D-03).
            LOG.warn("Vault cleanup will retry a record: {}", exception.getClass().getSimpleName());
            return false;
        }
    }

    /** 걱정과 연결 데이터를 지운다. 걱정 행이 더 이상 없으면 true다. */
    private boolean finish(Candidate candidate) {
        if (!guestWriteLock.lockActive(candidate.guestId())) {
            return false;
        }
        var now = OffsetDateTime.now(clock);
        var request = jdbc.sql("""
            SELECT request_id FROM worries WHERE id = :id AND guest_id = :guest
              AND (expires_at <= :now OR deletion_started_at IS NOT NULL)
            """).param("id", candidate.worryId()).param("guest", candidate.guestId())
            .param("now", now).query(UUID.class).optional();
        if (request.isPresent()) {
            // The guest lock serializes analyzer commits and all user edits. The FK clears the worry and its content.
            jdbc.sql("""
                UPDATE analysis_requests SET state = 'CANCELLED', input_ciphertext = NULL, submission_fingerprint = NULL,
                  publish_at = NULL, context_hash = NULL, guidance_until = NULL, attempt_id = NULL, lease_until = NULL
                WHERE id = :id
                """).param("id", request.get()).update();
            jdbc.sql("DELETE FROM analysis_retries WHERE request_id = :id").param("id", request.get()).update();
            jdbc.sql("DELETE FROM worries WHERE id = :id").param("id", candidate.worryId()).update();
            jdbc.sql("""
                INSERT INTO worry_deletion_receipts (worry_id, guest_id, completed_at) VALUES (:id, :guest, :now)
                ON CONFLICT (worry_id) DO NOTHING
                """).param("id", candidate.worryId()).param("guest", candidate.guestId()).param("now", now).update();
        }
        return true;
    }

    private record Candidate(UUID worryId, UUID guestId, OffsetDateTime expiresAt) {}
}
