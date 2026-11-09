package com.metroticket_api.payment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * app.payment.mock-enabled: cho phép CARD/E_WALLET/BANK_TRANSFER "thanh toán thành công ngay" khi mua online.
 * CHỈ bật ở dev/test – bật trên production là ai cũng lấy được vé miễn phí.
 */
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProps(
        @DefaultValue("false") boolean mockEnabled,
        @DefaultValue Sepay sepay) {

    /**
     * Cấu hình SePay. Webhook phải có ít nhất một cơ chế xác thực: webhookApiKey (header
     * {@code Authorization: Apikey ...}) hoặc webhookHmacSecret (header X-SePay-Signature). Thiếu cả hai thì
     * mọi webhook bị từ chối (fail-closed).
     */
    public record Sepay(
            @DefaultValue("false") boolean enabled,
            /** Tên ngân hàng theo qr.sepay.vn/banks.json, ví dụ MBBank, Vietcombank. */
            @DefaultValue("") String bank,
            @DefaultValue("") String accountNumber,
            @DefaultValue("") String accountHolder,
            @DefaultValue("") String webhookApiKey,
            @DefaultValue("") String webhookHmacSecret,
            /** Phải khớp "Cấu trúc mã thanh toán" khai báo trên SePay để SePay tách field code. */
            @DefaultValue("MTQ") String codePrefix,
            @DefaultValue("https://qr.sepay.vn/img") String qrImageUrl,
            @DefaultValue("5") int maxPendingOrdersPerUser,
            /** Chấp nhận lệch đồng hồ khi kiểm tra chữ ký HMAC. */
            @DefaultValue("5m") Duration signatureTolerance) {

        public boolean hasWebhookAuth() {
            return !webhookApiKey.isBlank() || !webhookHmacSecret.isBlank();
        }
    }
}
