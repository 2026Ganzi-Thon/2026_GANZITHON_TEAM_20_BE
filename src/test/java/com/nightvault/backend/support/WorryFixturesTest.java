package com.nightvault.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import com.nightvault.backend.support.WorryFixtures.WorrySpec;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class WorryFixturesTest extends IntegrationTest {

    /** BE2 설계 §3 공통 조건을 DB 시각으로 판정한다. */
    private String stateOf(TestWorry worry) {
        return jdbc.sql("""
                SELECT CASE
                    WHEN deletion_started_at IS NOT NULL THEN 'DELETING'
                    WHEN expires_at <= statement_timestamp() THEN 'EXPIRED'
                    WHEN publish_at > statement_timestamp() THEN 'LOCKED'
                    ELSE 'PUBLIC'
                END
                FROM worries WHERE id = :id AND guest_id = :guestId
                """)
            .param("id", worry.id())
            .param("guestId", worry.guestId())
            .query(String.class).single();
    }

    @Test
    void 게스트는_세션과_설정을_가진_ACTIVE_상태다() {
        TestGuest guest = fixtures.guest();

        assertThat(jdbc.sql("SELECT state FROM guests WHERE id = :id").param("id", guest.id())
            .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM guest_settings WHERE guest_id = :id").param("id", guest.id())
            .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM guest_sessions
                WHERE guest_id = :id AND expires_at > statement_timestamp()
                """).param("id", guest.id())
            .query(Long.class).single()).isOne();
        assertThat(guest.sessionToken()).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void 공개된_걱정() {
        TestWorry worry = fixtures.publicWorry(fixtures.guest());
        assertThat(stateOf(worry)).isEqualTo("PUBLIC");
    }

    @Test
    void 잠긴_걱정() {
        TestWorry worry = fixtures.lockedWorry(fixtures.guest());
        assertThat(stateOf(worry)).isEqualTo("LOCKED");
    }

    @Test
    void 삭제_중인_걱정() {
        TestWorry worry = fixtures.deletingWorry(fixtures.guest());
        assertThat(stateOf(worry)).isEqualTo("DELETING");
    }

    @Test
    void 만료된_걱정() {
        TestWorry worry = fixtures.expiredWorry(fixtures.guest());
        assertThat(stateOf(worry)).isEqualTo("EXPIRED");
    }

    @Test
    void 걱정은_분석_요청_SUCCEEDED와_AI_콘텐츠를_함께_갖는다() {
        TestWorry worry = fixtures.publicWorry(fixtures.guest());

        assertThat(jdbc.sql("SELECT state FROM analysis_requests WHERE id = :id AND guest_id = :guestId")
            .param("id", worry.requestId()).param("guestId", worry.guestId())
            .query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_contents WHERE worry_id = :id").param("id", worry.id())
            .query(Long.class).single()).isOne();
    }

    @Test
    void 게스트마다_걱정이_분리된다() {
        TestGuest owner = fixtures.guest();
        TestGuest other = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(owner);

        assertThat(jdbc.sql("SELECT count(*) FROM worries WHERE id = :id AND guest_id = :guestId")
            .param("id", worry.id()).param("guestId", other.id())
            .query(Long.class).single()).isZero();
    }

    @Test
    void 공개_시각_CHECK를_어기면_DB가_거부한다() {
        TestGuest guest = fixtures.guest();
        Instant created = WorryFixtures.now();
        WorrySpec invalid = WorrySpec.of(created, created.minus(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> fixtures.worry(guest, invalid))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 일반형으로_상태를_조합할_수_있다() {
        TestGuest guest = fixtures.guest();
        Instant created = WorryFixtures.now().minus(Duration.ofDays(3));
        TestWorry viewed = fixtures.worry(guest, WorrySpec.of(created, created.plus(Duration.ofDays(1)))
            .withFirstViewedAt(created.plus(Duration.ofDays(2))));

        assertThat(stateOf(viewed)).isEqualTo("PUBLIC");
        assertThat(jdbc.sql("SELECT first_viewed_at IS NOT NULL FROM worries WHERE id = :id")
            .param("id", viewed.id()).query(Boolean.class).single()).isTrue();
    }
}
