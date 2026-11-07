package com.metroticket_api.payment;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Endpoint SePay gọi khi tài khoản có giao dịch. Không dùng JWT (SePay không có), thay bằng API key / HMAC.
 * SePay coi là thành công khi nhận HTTP 200 + {@code {"success": true}}; mọi trường hợp khác sẽ được gửi lại.
 */
@RestController
@RequestMapping("/api/v1/payments/sepay")
@Tag(name = "Payments", description = "Webhook SePay")
public class SepayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SepayWebhookController.class);
    private static final Map<String, Object> OK = Map.of("success", true);

    private final SepayWebhookVerifier verifier;
    private final PaymentOrderService orders;
    private final ObjectMapper objectMapper;

    public SepayWebhookController(SepayWebhookVerifier verifier, PaymentOrderService orders, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.orders = orders;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Webhook SePay (chỉ SePay gọi)")
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = "X-SePay-Signature", required = false) String signature,
            @RequestHeader(name = "X-SePay-Timestamp", required = false) String timestamp,
            @RequestBody(required = false) byte[] rawBody) {
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        if (!verifier.verify(authorization, signature, timestamp, body)) {
            log.warn("Từ chối webhook SePay: sai xác thực");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false));
        }
        SepayWebhookPayload payload;
        try {
            payload = SepayWebhookPayload.of(objectMapper.readTree(body));
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Webhook SePay không đọc được: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false));
        }
        SepayResult result = orders.handleWebhook(payload);
        log.info("Webhook SePay {} ({} đ) → {}", payload.id(), payload.transferAmount(), result);
        return ResponseEntity.ok(OK);
    }
}
