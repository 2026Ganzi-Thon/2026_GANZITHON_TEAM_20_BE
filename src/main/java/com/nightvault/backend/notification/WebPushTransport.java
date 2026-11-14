package com.nightvault.backend.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import jakarta.annotation.PreDestroy;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class WebPushTransport implements PushTransport {
    private static final Set<String> HOSTS = Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
        "web.push.apple.com", "wns2-bl2p.notify.windows.com");

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final PushService service;
    private final String publicKey;

    WebPushTransport(@Value("${NIGHTVAULT_PUSH_PUBLIC_KEY:}") String publicKey,
                     @Value("${NIGHTVAULT_PUSH_PRIVATE_KEY:}") String privateKey,
                     @Value("${NIGHTVAULT_PUSH_SUBJECT:}") String subject) {
        Security.addProvider(new BouncyCastleProvider());
        this.publicKey = publicKey;
        if (publicKey.isBlank() && privateKey.isBlank() && subject.isBlank()) {
            service = null;
            return;
        }
        if (publicKey.isBlank() || privateKey.isBlank() || !(subject.startsWith("mailto:") || subject.startsWith("https://"))) {
            throw new IllegalArgumentException("Web Push requires the VAPID key pair and a mailto or HTTPS subject.");
        }
        try {
            service = new PushService(publicKey, privateKey, subject);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Web Push VAPID keys.");
        }
    }

    @Override
    public String publicKey() {
        return service == null ? null : publicKey;
    }

    @Override
    public void validateSubscription(Subscription subscription) {
        try {
            endpointUri(subscription.endpoint());
            var decoder = Base64.getUrlDecoder();
            var p256dh = subscription.p256dh();
            var auth = subscription.auth();
            if (p256dh == null || auth == null || p256dh.length() > 100 || auth.length() > 30
                || decoder.decode(p256dh).length != 65 || decoder.decode(auth).length != 16) {
                throw new IllegalArgumentException();
            }
            Utils.loadPublicKey(p256dh);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
    }

    @Override
    public SendResult send(Subscription subscription, byte[] payload) {
        try {
            URI endpoint = endpointUri(subscription.endpoint());
            var notification = new Notification(subscription.endpoint(), subscription.p256dh(), subscription.auth(), payload, 60);
            var prepared = service.preparePost(notification, Encoding.AES128GCM);
            byte[] encrypted;
            try (var body = prepared.getEntity().getContent()) {
                encrypted = body.readAllBytes();
            }
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(encrypted));
            for (var header : prepared.getAllHeaders()) {
                request.header(header.getName(), header.getValue());
            }
            int status = client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 404 || status == 410) {
                return SendResult.EXPIRED;
            }
            return status >= 200 && status < 300 ? SendResult.ACCEPTED : SendResult.FAILED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SendResult.FAILED;
        } catch (Exception exception) {
            return SendResult.FAILED;
        }
    }

    private static URI endpointUri(String endpoint) {
        if (endpoint == null || endpoint.length() > 2048) {
            throw new IllegalArgumentException();
        }
        URI uri = URI.create(endpoint);
        var host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
            || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException();
        }
        String hostname = host.toLowerCase(Locale.ROOT);
        if (!HOSTS.contains(hostname) && !hostname.endsWith(".push.apple.com") && !hostname.endsWith(".notify.windows.com")) {
            throw new IllegalArgumentException();
        }
        return uri;
    }

    @PreDestroy
    void close() {
        client.shutdownNow();
    }

}
