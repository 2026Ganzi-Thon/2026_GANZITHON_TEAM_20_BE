package com.nightvault.backend.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import com.nightvault.backend.guest.GuestWriteLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class VaultNotifications {
    private final JdbcClient jdbc;
    private final GuestWriteLock guestWriteLock;
    private final Clock clock;
    private final PushTransport transport;

    VaultNotifications(JdbcClient jdbc, Clock clock, PushTransport transport, GuestWriteLock guestWriteLock) {
        this.jdbc = jdbc;
        this.guestWriteLock = guestWriteLock;
        this.clock = clock;
        this.transport = transport;
    }

    Settings settings(UUID guest) {
        var current = jdbc.sql("""
            SELECT public_push_since IS NOT NULL AS enabled,
                   (SELECT count(*) FROM push_subscriptions p WHERE p.guest_id=s.guest_id) AS subscription_count
            FROM guest_settings s JOIN guests g ON g.id=s.guest_id AND g.state='ACTIVE'
            WHERE s.guest_id=:guest
            """).param("guest", guest).query((row, index) ->
            new Settings(transport.publicKey(), row.getBoolean("enabled"), row.getLong("subscription_count")))
            .optional();
        return current.orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND));
    }

    @Transactional
    public void subscribe(UUID guest, PushTransport.Subscription subscription) {
        if (transport.publicKey() == null) {
            throw new BusinessException(ApiError.TEMPORARILY_UNAVAILABLE);
        }
        transport.validateSubscription(subscription);
        guestWriteLock.requireActive(guest, ApiError.NOT_FOUND);
        jdbc.sql("""
            INSERT INTO push_subscriptions (guest_id, endpoint, p256dh, auth, subscribed_at)
            VALUES (:guest, :endpoint, :key, :auth, :now)
            ON CONFLICT (guest_id, endpoint) DO UPDATE SET p256dh=EXCLUDED.p256dh, auth=EXCLUDED.auth
            """).param("guest", guest).param("endpoint", subscription.endpoint())
            .param("key", subscription.p256dh()).param("auth", subscription.auth())
            .param("now", OffsetDateTime.now(clock)).update();
    }

    @Transactional
    public void consent(UUID guest, boolean enabled) {
        guestWriteLock.requireActive(guest, ApiError.NOT_FOUND);
        jdbc.sql("""
            UPDATE guest_settings SET public_push_since =
                CASE WHEN :enabled THEN COALESCE(public_push_since, :now) ELSE NULL END
            WHERE guest_id=:guest
            """).param("enabled", enabled).param("now", OffsetDateTime.now(clock)).param("guest", guest).update();
    }

    @Transactional
    public void disable(UUID guest) {
        guestWriteLock.requireActive(guest, ApiError.NOT_FOUND);
        jdbc.sql("UPDATE guest_settings SET public_push_since=NULL WHERE guest_id=:guest").param("guest", guest).update();
        jdbc.sql("DELETE FROM push_subscriptions WHERE guest_id=:guest").param("guest", guest).update();
    }

    Reminder reminder(UUID guest) {
        return jdbc.sql("""
            SELECT r.worry_id,r.send_at FROM vault_reminders r JOIN worries w ON w.id=r.worry_id
            JOIN guests g ON g.id=r.guest_id AND g.state='ACTIVE'
            WHERE r.guest_id=:guest AND w.deletion_started_at IS NULL AND w.expires_at>:now
            """).param("guest", guest).param("now", OffsetDateTime.now(clock))
            .query((row, index) -> new Reminder(row.getObject("worry_id", UUID.class),
                row.getObject("send_at", OffsetDateTime.class))).optional().orElse(null);
    }

    @Transactional
    public Reminder schedule(UUID guest, UUID worry, String endpoint, int minutes) {
        if ((minutes != 30 && minutes != 60) || endpoint == null || endpoint.length() > 2048) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        guestWriteLock.requireActive(guest, ApiError.NOT_FOUND);
        var now = OffsetDateTime.now(clock);
        var sendAt = now.plusMinutes(minutes);
        int count = jdbc.sql("""
            INSERT INTO vault_reminders (guest_id,worry_id,endpoint,send_at)
            SELECT :guest,w.id,p.endpoint,:sendAt FROM worries w
            JOIN push_subscriptions p ON p.guest_id=w.guest_id AND p.endpoint=:endpoint
            WHERE w.id=:id AND w.guest_id=:guest AND w.deletion_started_at IS NULL AND w.expires_at>:sendAt
            ON CONFLICT (guest_id) DO UPDATE SET worry_id=EXCLUDED.worry_id, endpoint=EXCLUDED.endpoint, send_at=EXCLUDED.send_at
            """).param("guest", guest).param("id", worry).param("endpoint", endpoint).param("sendAt", sendAt).update();
        if (count == 0) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
        return new Reminder(worry, sendAt);
    }

    @Transactional
    public void cancel(UUID guest) {
        guestWriteLock.requireActive(guest, ApiError.NOT_FOUND);
        jdbc.sql("DELETE FROM vault_reminders WHERE guest_id=:guest").param("guest", guest).update();
    }

    record Settings(String vapidPublicKey, boolean publicationEnabled, long subscriptionCount) {}
    record Reminder(UUID worryId, OffsetDateTime sendAt) {}
}
