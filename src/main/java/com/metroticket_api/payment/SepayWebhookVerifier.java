package com.metroticket_api.payment;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Xác thực webhook SePay (developer.sepay.vn/vi/sepay-webhooks/xac-thuc):
 * <ul>
 *   <li>HMAC-SHA256 (ưu tiên nếu cấu hình secret): {@code X-SePay-Signature: sha256=<hex>} ký trên
 *       {@code "<X-SePay-Timestamp>.<raw body>"}, timestamp lệch quá tolerance thì từ chối (chống replay).</li>
 *   <li>API Key: {@code Authorization: Apikey <key>}.</li>
 * </ul>
 * So sánh bằng {@link MessageDigest#isEqual} (thời gian hằng). Không cấu hình gì → từ chối tất cả.
 */
@Component
public class SepayWebhookVerifier {

    private static final String API_KEY_PREFIX = "Apikey ";

    private final PaymentProps.Sepay props;
    private final Clock clock;

    public SepayWebhookVerifier(PaymentProps props, Clock clock) {
        this.props = props.sepay();
        this.clock = clock;
    }

    public boolean verify(String authorization, String signature, String timestamp, byte[] rawBody) {
        if (!props.webhookHmacSecret().isBlank()) {
            return verifyHmac(signature, timestamp, rawBody);
        }
        if (!props.webhookApiKey().isBlank()) {
            return verifyApiKey(authorization);
        }
        return false;
    }

    boolean verifyApiKey(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, API_KEY_PREFIX, 0, API_KEY_PREFIX.length())) {
            return false;
        }
        String provided = authorization.substring(API_KEY_PREFIX.length()).trim();
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                props.webhookApiKey().getBytes(StandardCharsets.UTF_8));
    }

    boolean verifyHmac(String signature, String timestamp, byte[] rawBody) {
        if (signature == null || timestamp == null || !signature.startsWith("sha256=")) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - ts) > props.signatureTolerance().toSeconds()) {
            return false;
        }
        String expected = "sha256=" + sign(props.webhookHmacSecret(), timestamp.trim(), rawBody);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    static String sign(String secret, String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Không khởi tạo được HMAC-SHA256", e);
        }
    }
}
