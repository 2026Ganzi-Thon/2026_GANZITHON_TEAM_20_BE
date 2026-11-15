package com.nightvault.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.nightvault.backend.guest.GuestWriteLock;
import com.nightvault.backend.support.IntegrationTest;
import com.nightvault.backend.support.WorryFixtures;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시연 게스트에게는 실제 푸시가 나가지 않는다(DEMO-02). 컨텍스트를 늘리지 않으려고
 * 기록용 전송 객체를 넣은 {@link NotificationDispatch}를 직접 만든다.
 */
class DemoNotificationExclusionTest extends IntegrationTest {
    @Autowired
    JsonMapper json;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    GuestWriteLock guestWriteLock;

    @Test
    void 공개_알림과_재알림은_일반_게스트에게만_나간다() {
        TestGuest regular = subscribedGuestWithPublicWorry("https://push.example/regular");
        TestGuest demo = subscribedGuestWithPublicWorry("https://push.example/demo");
        jdbc.sql("UPDATE guests SET kind = 'DEMO' WHERE id = :id").param("id", demo.id()).update();
        var reminderWorry = fixtures.publicWorry(demo);
        jdbc.sql("""
            INSERT INTO vault_reminders (guest_id, worry_id, endpoint, send_at)
            VALUES (:guest, :worry, 'https://push.example/demo', :sendAt)
            """).param("guest", demo.id()).param("worry", reminderWorry.id())
            .param("sendAt", clock.instant().minusSeconds(60).atOffset(ZoneOffset.UTC)).update();

        var transport = new RecordingTransport();
        new NotificationDispatch(jdbc, clock, transport, json, transaction, guestWriteLock).dispatch();

        assertThat(transport.endpoints).containsExactly("https://push.example/regular");
        assertThat(jdbc.sql("SELECT count(*) FROM publication_push_receipts WHERE guest_id = :id")
            .param("id", demo.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM vault_reminders WHERE guest_id = :id")
            .param("id", demo.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(regular).isNotNull();
    }

    /** 알림 동의·구독을 3일 전에 마친 게스트 + 하루 전에 공개된 미열람 걱정. */
    private TestGuest subscribedGuestWithPublicWorry(String endpoint) {
        TestGuest guest = fixtures.guest();
        var since = WorryFixtures.now().minus(Duration.ofDays(3)).atOffset(ZoneOffset.UTC);
        jdbc.sql("UPDATE guest_settings SET public_push_since = :since WHERE guest_id = :id")
            .param("since", since).param("id", guest.id()).update();
        jdbc.sql("""
            INSERT INTO push_subscriptions (guest_id, endpoint, p256dh, auth, subscribed_at)
            VALUES (:id, :endpoint, 'key', 'auth', :since)
            """).param("id", guest.id()).param("endpoint", endpoint).param("since", since).update();
        fixtures.publicWorry(guest);
        return guest;
    }

    private static final class RecordingTransport implements PushTransport {
        final List<String> endpoints = new ArrayList<>();

        @Override
        public String publicKey() {
            return "test-public-key";
        }

        @Override
        public void validateSubscription(Subscription subscription) {}

        @Override
        public SendResult send(Subscription subscription, byte[] payload) {
            endpoints.add(subscription.endpoint());
            return SendResult.ACCEPTED;
        }
    }
}
