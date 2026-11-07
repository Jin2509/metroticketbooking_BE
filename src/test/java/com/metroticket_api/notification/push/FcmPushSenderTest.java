package com.metroticket_api.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.metroticket_api.notification.push.PushSender.Message;
import com.metroticket_api.notification.push.PushSender.Outcome;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** FCM giả lập (token endpoint + messages:send) bằng HTTP server local. */
class FcmPushSenderTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private KeyPair keys;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicReference<String> assertion = new AtomicReference<>();
    private final AtomicReference<String> sentBody = new AtomicReference<>();
    private final AtomicReference<String> sentAuth = new AtomicReference<>();
    private volatile int sendStatus = 200;
    private volatile String sendResponse = "{\"name\":\"projects/metro/messages/1\"}";

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            tokenCalls.incrementAndGet();
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String part : form.split("&")) {
                if (part.startsWith("assertion=")) {
                    assertion.set(URLDecoder.decode(part.substring(10), StandardCharsets.UTF_8));
                }
            }
            reply(ex, 200, "{\"access_token\":\"ya29.test\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        });
        server.createContext("/v1/projects/metro/messages:send", ex -> {
            sentAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            sentBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, sendStatus, sendResponse);
        });
        server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private FcmPushSender sender(boolean base64) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String json;
        try {
            json = mapper.writeValueAsString(Map.of("type", "service_account", "project_id", "metro",
                    "client_email", "push@metro.iam.gserviceaccount.com", "private_key", pem, "token_uri", base + "/token"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String config = base64 ? Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)) : json;
        return new FcmPushSender(new FcmProps(true, config, base, Duration.ofSeconds(5)), mapper,
                Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void signsServiceAccountJwtAndSendsMessage() throws Exception {
        FcmPushSender fcm = sender(false);
        assertThat(fcm.available()).isTrue();
        var result = fcm.send("device-token-1", new Message("Tàu chậm", "Tuyến số 1 chậm 10 phút", true,
                Map.of("link", "/announcements/5")));
        assertThat(result.outcome()).isEqualTo(Outcome.SENT);
        assertThat(sentAuth.get()).isEqualTo("Bearer ya29.test");

        JsonNode msg = mapper.readTree(sentBody.get()).path("message");
        assertThat(msg.path("token").asString()).isEqualTo("device-token-1");
        assertThat(msg.path("notification").path("title").asString()).isEqualTo("Tàu chậm");
        assertThat(msg.path("data").path("link").asString()).isEqualTo("/announcements/5");
        assertThat(msg.path("android").path("priority").asString()).isEqualTo("HIGH");
        assertThat(msg.path("apns").path("headers").path("apns-priority").asString()).isEqualTo("10");

        // JWT RS256 ký bằng private key của service account, đúng scope + audience
        String[] parts = assertion.get().split("\\.");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("iss").asString()).isEqualTo("push@metro.iam.gserviceaccount.com");
        assertThat(claims.path("scope").asString()).isEqualTo(FcmPushSender.SCOPE);
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(3600);

        // access token được cache
        fcm.send("device-token-2", new Message("a", "b", false, Map.of()));
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void classifiesErrors() {
        FcmPushSender fcm = sender(true);
        sendStatus = 404;
        sendResponse = "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}";
        assertThat(fcm.send("dead", new Message("a", "b", false, Map.of())).outcome()).isEqualTo(Outcome.INVALID_TOKEN);
        sendStatus = 503;
        sendResponse = "{}";
        assertThat(fcm.send("x", new Message("a", "b", false, Map.of())).outcome()).isEqualTo(Outcome.RETRY);
        sendStatus = 429;
        assertThat(fcm.send("x", new Message("a", "b", false, Map.of())).outcome()).isEqualTo(Outcome.RETRY);
        sendStatus = 403;
        sendResponse = "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}";
        assertThat(fcm.send("x", new Message("a", "b", false, Map.of())).outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void disabledWhenNotConfiguredOrInvalid() {
        assertThat(new FcmPushSender(new FcmProps(false, "", "http://x", Duration.ofSeconds(1)), mapper,
                Clock.systemUTC()).available()).isFalse();
        assertThat(new FcmPushSender(new FcmProps(true, "{\"private_key\":\"not-a-key\"}", "http://x", Duration.ofSeconds(1)),
                mapper, Clock.systemUTC()).available()).as("JSON sai → tắt push, không làm sập app").isFalse();
    }
}
