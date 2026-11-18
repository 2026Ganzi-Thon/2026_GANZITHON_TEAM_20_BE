package com.nightvault.backend.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.nightvault.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class VaultCleanupTest extends IntegrationTest {
    @Autowired
    VaultDeletion deletion;

    @Test
    void cleanupRemovesPendingAndExpiredRecordsAndCanRunTwice() {
        var guest = fixtures.guest();
        var pending = fixtures.deletingWorry(guest);
        var expired = fixtures.expiredWorry(guest);
        var current = fixtures.publicWorry(guest);

        deletion.cleanUp();
        deletion.cleanUp();

        assertThat(worryExists(pending.id())).isFalse();
        assertThat(worryExists(expired.id())).isFalse();
        assertThat(worryExists(current.id())).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_contents").query(Integer.class).single()).isOne();
        assertThat(deletion.deleteWorry(guest.id(), pending.id())).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM analysis_requests WHERE id = :id")
            .param("id", expired.requestId()).query(Integer.class).single()).isZero();
    }

    private boolean worryExists(UUID worryId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM worries WHERE id = :id)")
            .param("id", worryId).query(Boolean.class).single();
    }
}
