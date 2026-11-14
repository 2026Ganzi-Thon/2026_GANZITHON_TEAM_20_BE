package com.nightvault.backend.notification;

public interface PushTransport {
    String publicKey();

    void validateSubscription(Subscription subscription);

    SendResult send(Subscription subscription, byte[] payload);

    record Subscription(String endpoint, String p256dh, String auth) {}
    enum SendResult { ACCEPTED, EXPIRED, FAILED }
}
