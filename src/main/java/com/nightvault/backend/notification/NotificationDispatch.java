package com.nightvault.backend.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.guest.GuestWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
class NotificationDispatch {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatch.class);
    private static final String ELIGIBLE_PUBLICATIONS = """
        FROM worries w JOIN guest_settings s ON s.guest_id=w.guest_id
        WHERE w.guest_id=p.guest_id AND w.first_viewed_at IS NULL AND w.deletion_started_at IS NULL
          AND w.expires_at>:now AND w.publish_at<=:now AND w.publish_at>s.public_push_since
          AND w.publish_at>p.subscribed_at
          AND (w.sealed_at<=w.publish_at OR w.created_at>=s.public_push_since)
          AND NOT EXISTS (SELECT 1 FROM publication_push_receipts r
                          WHERE r.worry_id=w.id AND r.guest_id=p.guest_id AND r.endpoint=p.endpoint)
        """;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final PushTransport transport;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final GuestWriteLock guestWriteLock;

    NotificationDispatch(JdbcClient jdbc, Clock clock, PushTransport transport, JsonMapper json,
                         TransactionTemplate transaction, GuestWriteLock guestWriteLock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transport = transport;
        this.json = json;
        this.transaction = transaction;
        this.guestWriteLock = guestWriteLock;
    }

    @Scheduled(fixedDelay = 5000)
    void dispatch() {
        if (transport.publicKey() == null) {
            return;
        }
        var now = OffsetDateTime.now(clock);
        var due = jdbc.sql("""
            SELECT p.guest_id,p.endpoint
            FROM push_subscriptions p JOIN guests g ON g.id=p.guest_id AND g.state='ACTIVE'
            WHERE EXISTS (SELECT 1
            """ + ELIGIBLE_PUBLICATIONS + """
               )
               OR EXISTS (SELECT 1 FROM vault_reminders r WHERE r.guest_id=p.guest_id AND r.endpoint=p.endpoint AND r.send_at<=:now)
            ORDER BY p.guest_id,p.endpoint LIMIT 20
            """).param("now", now).query((row, index) -> new Target(row.getObject("guest_id", UUID.class),
                row.getString("endpoint"))).list();
        for (var target : due) {
            try {
                var delivery = transaction.execute(status -> claim(target));
                if (delivery != null) {
                    send(delivery);
                }
            } catch (RuntimeException exception) {
                LOG.warn("Web Push job could not be processed");
            }
        }
    }

    // Claim one scheduled event while holding the guest lock, then release it before network I/O.
    private Delivery claim(Target target) {
        var guest = target.guestId();
        var endpoint = target.endpoint();
        if (!guestWriteLock.lockActive(guest)) {
            return null;
        }
        var subscription = jdbc.sql("""
            SELECT p256dh,auth,subscribed_at FROM push_subscriptions WHERE guest_id=:guest AND endpoint=:endpoint
            """).param("guest", guest).param("endpoint", endpoint).query((row, index) ->
            new RegisteredSubscription(new PushTransport.Subscription(endpoint, row.getString("p256dh"),
                row.getString("auth")), row.getObject("subscribed_at", OffsetDateTime.class))).optional();
        if (subscription.isEmpty()) {
            return null;
        }
        var now = OffsetDateTime.now(clock);
        var published = jdbc.sql("""
            SELECT eligible.id,eligible.publish_at
            FROM push_subscriptions p
            CROSS JOIN LATERAL (
                SELECT w.id,w.publish_at
            """ + ELIGIBLE_PUBLICATIONS + """
                ORDER BY w.publish_at FETCH FIRST 1 ROW WITH TIES
            ) eligible
            WHERE p.guest_id=:guest AND p.endpoint=:endpoint
            """)
            .param("guest", guest).param("endpoint", endpoint).param("now", now)
            .query((row, index) -> new Publication(row.getObject("id", UUID.class),
                row.getObject("publish_at", OffsetDateTime.class))).list();
        var reminder = jdbc.sql("""
            SELECT worry_id,send_at FROM vault_reminders WHERE guest_id=:guest AND endpoint=:endpoint AND send_at<=:now
            """).param("guest", guest).param("endpoint", endpoint).param("now", now)
            .query((row, index) -> new VaultNotifications.Reminder(row.getObject("worry_id", UUID.class),
                row.getObject("send_at", OffsetDateTime.class))).optional().orElse(null);
        if (!published.isEmpty() && (reminder == null || !published.getFirst().at().isAfter(reminder.sendAt()))) {
            jdbc.sql("""
                INSERT INTO publication_push_receipts (worry_id,guest_id,endpoint)
                SELECT w.id,:guest,:endpoint FROM worries w WHERE w.id IN (:ids)
                ON CONFLICT DO NOTHING
                """).param("ids", published.stream().map(Publication::id).toList())
                .param("guest", guest).param("endpoint", endpoint).update();
            if (reminder != null && published.getFirst().at().isEqual(reminder.sendAt())) {
                consumeReminder(guest);
            }
            return new Delivery(guest, subscription.get(), published.stream().map(Publication::id).toList(), null);
        }
        if (reminder == null) {
            return null;
        }
        consumeReminder(guest);
        return new Delivery(guest, subscription.get(), List.of(), reminder.worryId());
    }

    private void consumeReminder(UUID guest) {
        jdbc.sql("DELETE FROM vault_reminders WHERE guest_id=:guest").param("guest", guest).update();
    }

    private void send(Delivery delivery) {
        var registered = delivery.subscription();
        var subscription = registered.value();
        var message = eligibleMessage(delivery);
        if (message == null) {
            return;
        }
        var result = transport.send(subscription, json.writeValueAsBytes(message));
        if (result == PushTransport.SendResult.EXPIRED) {
            transaction.executeWithoutResult(status -> {
                if (guestWriteLock.lockActive(delivery.guestId())) {
                    jdbc.sql("""
                        DELETE FROM push_subscriptions WHERE guest_id=:guest AND endpoint=:endpoint
                          AND p256dh=:key AND auth=:auth AND subscribed_at=:subscribed
                        """).param("guest", delivery.guestId()).param("endpoint", subscription.endpoint())
                        .param("key", subscription.p256dh()).param("auth", subscription.auth())
                        .param("subscribed", registered.at()).update();
                }
            });
        } else if (result == PushTransport.SendResult.FAILED) {
            LOG.warn("Web Push delivery was not accepted");
        }
    }

    private Message eligibleMessage(Delivery delivery) {
        var registered = delivery.subscription();
        var subscription = registered.value();
        boolean reminder = delivery.reminderWorryId() != null;
        String publication = reminder ? "" : """
              AND w.publish_at<=:now AND w.first_viewed_at IS NULL AND w.publish_at>s.public_push_since
              AND w.publish_at>p.subscribed_at AND (w.sealed_at<=w.publish_at OR w.created_at>=s.public_push_since)
            """;
        long count = jdbc.sql("""
            SELECT count(*) FROM worries w
            JOIN guests g ON g.id=w.guest_id AND g.state='ACTIVE'
            JOIN guest_settings s ON s.guest_id=w.guest_id
            JOIN push_subscriptions p ON p.guest_id=w.guest_id
            WHERE w.guest_id=:guest AND w.id IN (:ids) AND w.deletion_started_at IS NULL AND w.expires_at>:now
              AND p.endpoint=:endpoint AND p.p256dh=:key AND p.auth=:auth AND p.subscribed_at=:subscribed
            """ + publication)
            .param("guest", delivery.guestId()).param("ids", reminder ? List.of(delivery.reminderWorryId()) : delivery.publicationIds())
            .param("now", OffsetDateTime.now(clock)).param("endpoint", subscription.endpoint())
            .param("key", subscription.p256dh()).param("auth", subscription.auth()).param("subscribed", registered.at())
            .query(Long.class).single();
        if (count == 0) {
            return null;
        }
        if (reminder) {
            return new Message("말씀하신 시간이 됐어요", "가능할 때 잠시 쉬어가세요. 앱을 다시 열지 않아도 괜찮아요.", "/");
        }
        String body = count == 1 ? "맡겨둔 걱정을 확인할 수 있어요." : "맡겨둔 걱정 " + count + "개를 확인할 수 있어요.";
        return new Message("금고가 열렸어요", body, "/vault");
    }

    private record Target(UUID guestId, String endpoint) {}
    private record Publication(UUID id, OffsetDateTime at) {}
    private record RegisteredSubscription(PushTransport.Subscription value, OffsetDateTime at) {}
    private record Delivery(UUID guestId, RegisteredSubscription subscription, List<UUID> publicationIds,
                            UUID reminderWorryId) {}
    private record Message(String title, String body, String url) {}
}
