package com.metroticket_api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SepayWebhookVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
    private static final byte[] BODY = "{\"id\":1,\"transferAmount\":7000}".getBytes(StandardCharsets.UTF_8);

    static PaymentProps props(String apiKey, String hmac) {
        return new PaymentProps(false, new PaymentProps.Sepay(true, "MBBank", "0123456789", "METRO", apiKey, hmac,
                "MTQ", Duration.ofMinutes(15), "https://qr.sepay.vn/img", 5, Duration.ofMinutes(5)));
    }

    private static SepayWebhookVerifier verifier(String apiKey, String hmac) {
        return new SepayWebhookVerifier(props(apiKey, hmac), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void apiKeyMustMatchExactly() {
        SepayWebhookVerifier v = verifier("secret-key-123", "");
        assertThat(v.verify("Apikey secret-key-123", null, null, BODY)).isTrue();
        assertThat(v.verify("apikey secret-key-123", null, null, BODY)).as("scheme không phân biệt hoa thường").isTrue();
        assertThat(v.verify("Apikey secret-key-12", null, null, BODY)).isFalse();
        assertThat(v.verify("Bearer secret-key-123", null, null, BODY)).isFalse();
        assertThat(v.verify(null, null, null, BODY)).isFalse();
    }

    @Test
    void hmacSignatureOverTimestampAndRawBody() {
        SepayWebhookVerifier v = verifier("", "hmac-secret");
        String ts = String.valueOf(NOW.getEpochSecond());
        String sig = "sha256=" + SepayWebhookVerifier.sign("hmac-secret", ts, BODY);
        assertThat(v.verify(null, sig, ts, BODY)).isTrue();

        byte[] tampered = "{\"id\":1,\"transferAmount\":9000000}".getBytes(StandardCharsets.UTF_8);
        assertThat(v.verify(null, sig, ts, tampered)).as("sửa body").isFalse();

        String old = String.valueOf(NOW.minusSeconds(600).getEpochSecond());
        String oldSig = "sha256=" + SepayWebhookVerifier.sign("hmac-secret", old, BODY);
        assertThat(v.verify(null, oldSig, old, BODY)).as("replay quá 5 phút").isFalse();

        assertThat(v.verify("Apikey anything", null, null, BODY)).as("đã cấu hình HMAC thì không nhận API key").isFalse();
    }

    @Test
    void noSecretConfiguredRejectsEverything() {
        assertThat(verifier("", "").verify("Apikey ", null, null, BODY)).isFalse();
    }

    @Test
    void extractsOrderCodeEvenWhenBankMangledContent() {
        PaymentOrderService service = new PaymentOrderService(mock(PaymentOrderRepository.class),
                mock(SepayTransactionRepository.class), mock(PaymentService.class), props("k", ""), List.of(),
                Clock.systemUTC(), null);
        assertThat(service.extractCode(payload(null, "CK MTQ7KQ2X9HD thanh toan ve"))).contains("MTQ7KQ2X9HD");
        assertThat(service.extractCode(payload("MTQ7KQ2X9HD", "abc"))).contains("MTQ7KQ2X9HD");
        assertThat(service.extractCode(payload(null, "mtq-7kq2-x9hd"))).contains("MTQ7KQ2X9HD");
        assertThat(service.extractCode(payload(null, "chuyen tien an trua"))).isEqualTo(Optional.empty());
    }

    @Test
    void sepayQrCannotBeRecordedAsCollectedOnTheSpot() {
        PaymentService service = new PaymentService(mock(PaymentTransactionRepository.class), Clock.systemUTC(), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.charge(null, PaymentPurpose.PARKING_SESSION,
                "x", 5000, PaymentMethod.SEPAY_QR)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private static SepayWebhookPayload payload(String code, String content) {
        return new SepayWebhookPayload(1, "MBBank", null, "0123456789", null, code, content, "in", 7000, null, null);
    }
}
