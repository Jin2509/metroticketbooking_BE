package com.metroticket_api.notification.push;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gửi push qua FCM HTTP v1: POST {apiBaseUrl}/v1/projects/{projectId}/messages:send, Bearer access token
 * lấy bằng JWT của service account (scope firebase.messaging), token được cache tới gần hết hạn.
 */
@Component
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final FcmProps props;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RestClient http;
    private final ServiceAccount account;
    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    record ServiceAccount(String projectId, String clientEmail, PrivateKey privateKey, String tokenUri) {
    }

    public FcmPushSender(FcmProps props, ObjectMapper mapper, Clock clock) {
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        this.http = RestClient.builder().requestFactory(factory).build();
        ServiceAccount parsed = null;
        if (props.configured()) {
            try {
                parsed = parse(props.serviceAccountJson());
            } catch (RuntimeException e) {
                log.error("FCM service account không hợp lệ, tắt push: {}", e.getMessage());
            }
        }
        this.account = parsed;
    }

    @Override
    public boolean available() {
        return account != null;
    }

    @Override
    public Result send(String deviceToken, Message message) {
        if (!available()) {
            return new Result(Outcome.FAILED, "FCM chưa được cấu hình");
        }
        Map<String, Object> notification = Map.of("title", message.title(), "body", message.body());
        Map<String, Object> body = Map.of("message", Map.of(
                "token", deviceToken,
                "notification", notification,
                "data", message.data() == null ? Map.of() : message.data(),
                "android", Map.of("priority", message.highPriority() ? "HIGH" : "NORMAL"),
                "apns", Map.of("headers", Map.of("apns-priority", message.highPriority() ? "10" : "5"))));
        try {
            http.post().uri(props.apiBaseUrl() + "/v1/projects/" + account.projectId() + "/messages:send")
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return Result.sent();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            String text = e.getResponseBodyAsString();
            if (code == 404 || text.contains("UNREGISTERED") || (code == 400 && text.contains("registration token"))) {
                return new Result(Outcome.INVALID_TOKEN, "FCM " + code + ": token không còn hiệu lực");
            }
            if (code == 401) {
                accessTokenExpiry = Instant.EPOCH; // làm mới token ở lần sau
                return new Result(Outcome.RETRY, "FCM 401");
            }
            boolean retry = code == 429 || code >= 500;
            return new Result(retry ? Outcome.RETRY : Outcome.FAILED, "FCM " + code + ": " + truncate(text, 200));
        } catch (ResourceAccessException e) {
            return new Result(Outcome.RETRY, "Không kết nối được FCM: " + e.getMessage());
        } catch (RuntimeException e) {
            return new Result(Outcome.RETRY, "Lỗi lấy access token FCM: " + e.getMessage());
        }
    }

    synchronized String accessToken() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpiry.minusSeconds(60))) {
            return accessToken;
        }
        String assertion = jwt(account, now);
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        String raw = http.post().uri(account.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode json = mapper.readTree(raw);
        accessToken = json.path("access_token").asString();
        accessTokenExpiry = now.plusSeconds(json.path("expires_in").asLong(3600));
        return accessToken;
    }

    static String jwt(ServiceAccount account, Instant now) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payloadJson = "{\"iss\":\"" + account.clientEmail() + "\",\"scope\":\"" + SCOPE + "\",\"aud\":\""
                + account.tokenUri() + "\",\"iat\":" + now.getEpochSecond() + ",\"exp\":" + (now.getEpochSecond() + 3600) + "}";
        String payload = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(account.privateKey());
            signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + b64.encodeToString(signer.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Không ký được JWT cho FCM", e);
        }
    }

    ServiceAccount parse(String raw) {
        String json = raw.trim().startsWith("{") ? raw.trim()
                : new String(Base64.getDecoder().decode(raw.trim()), StandardCharsets.UTF_8);
        JsonNode n = mapper.readTree(json);
        String pem = n.path("private_key").asString("");
        String base64Key = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Key)));
            return new ServiceAccount(n.path("project_id").asString(), n.path("client_email").asString(), key,
                    n.path("token_uri").asString("https://oauth2.googleapis.com/token"));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("private_key không hợp lệ", e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
