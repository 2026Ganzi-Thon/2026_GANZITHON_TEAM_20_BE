package com.nightvault.backend.deletion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import com.nightvault.backend.guest.GuestWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class VaultDeletion {
    private static final Logger LOG = LoggerFactory.getLogger(VaultDeletion.class);
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

    boolean deleteWorry(UUID guestId, UUID worryId) {
        boolean pending = Boolean.TRUE.equals(transaction.execute(status -> {
            guestWriteLock.requireActive(guestId, ApiError.NOT_FOUND);
            var now = OffsetDateTime.now(clock);
            if (jdbc.sql("""
                UPDATE worries SET deletion_started_at = COALESCE(deletion_started_at, :now)
                WHERE id = :id AND guest_id = :guest AND (expires_at > :now OR deletion_started_at IS NOT NULL)
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
        // The scheduler completes accepted deletions, even after an interrupted client request.
        return !pending;
    }

    void deleteVault(UUID guestId) {
        transaction.executeWithoutResult(status -> {
            guestWriteLock.requireActive(guestId, ApiError.NOT_FOUND);
            jdbc.sql("DELETE FROM guests WHERE id = :id").param("id", guestId).update();
        });
    }

    @Scheduled(fixedDelay = 5000)
    void cleanUp() {
        var now = OffsetDateTime.now(clock);
        var candidates = jdbc.sql("""
            SELECT id, guest_id FROM worries
            WHERE expires_at <= :now OR deletion_started_at IS NOT NULL
            ORDER BY expires_at, id LIMIT 100
            """).param("now", now)
            .query((row, index) -> new Candidate(row.getObject("id", UUID.class),
                row.getObject("guest_id", UUID.class))).list();
        for (var candidate : candidates) {
            try {
                transaction.executeWithoutResult(status -> finish(candidate));
            } catch (DataAccessException | TransactionException exception) {
                LOG.warn("Vault cleanup will retry a record");
            }
        }
        jdbc.sql("DELETE FROM worry_deletion_receipts WHERE completed_at <= :cutoff")
            .param("cutoff", now.minusDays(30)).update();
        jdbc.sql("DELETE FROM reflection_skips WHERE local_date < :cutoff").param("cutoff", now.toLocalDate().minusDays(2)).update();
        jdbc.sql("DELETE FROM analysis_requests WHERE expires_at <= :now AND NOT EXISTS (SELECT 1 FROM worries WHERE request_id = analysis_requests.id)")
            .param("now", now).update();
    }

    private void finish(Candidate candidate) {
        if (!guestWriteLock.lockActive(candidate.guestId())) {
            return;
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
    }

    private record Candidate(UUID worryId, UUID guestId) {}
}
