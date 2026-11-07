package com.metroticket_api.payment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.ticket.SecureCodes;

/**
 * Thanh toán chuyển khoản bằng mã VietQR, SePay báo kết quả qua webhook.
 *
 * <pre>
 * Mua vé (SEPAY_QR) → vé PENDING_PAYMENT + đơn PENDING (mã MTQxxxxxxxx, hết hạn sau order-ttl)
 *   → app hiển thị ảnh QR (số tiền + nội dung = mã đơn) → khách chuyển khoản
 *   → SePay POST webhook → xác thực → khớp mã đơn + số tiền → đơn PAID, vé ACTIVE
 * Hết hạn chưa trả → đơn EXPIRED, vé CANCELLED (job mỗi phút).
 * </pre>
 *
 * <p>Quy tắc khớp tiền:</p>
 * <ul>
 *   <li>Chỉ xử lý mỗi giao dịch SePay đúng một lần (khoá chính = id SePay); SePay gửi lại thì trả thành công luôn.</li>
 *   <li>Bỏ qua tiền ra và giao dịch không phải tài khoản thụ hưởng đã cấu hình.</li>
 *   <li>Chuyển thiếu: đơn vẫn chờ, ghi AMOUNT_MISMATCH để đối soát. Chuyển thừa: vẫn giao, ghi chú phần thừa.</li>
 *   <li>Tiền về sau khi đơn hết hạn: kích hoạt lại nếu hàng còn dùng được, không thì NEEDS_REFUND.
 *       Đơn khách tự huỷ hoặc trả 2 lần: NEEDS_REFUND.</li>
 * </ul>
 */
@Service
public class PaymentOrderService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderService.class);
    static final int CODE_SUFFIX_LENGTH = 8;

    public record PaymentInstruction(String orderCode, int amountVnd, PaymentOrderStatus status, Instant expiresAt,
            String bank, String accountNumber, String accountHolder, String transferContent, String qrImageUrl,
            Instant paidAt) {
    }

    private final PaymentOrderRepository orders;
    private final SepayTransactionRepository sepayTransactions;
    private final PaymentService payments;
    private final PaymentProps.Sepay sepay;
    private final Map<PaymentPurpose, PaymentFulfiller> fulfillers = new EnumMap<>(PaymentPurpose.class);
    private final Pattern codePattern;
    private final Clock clock;
    private final com.metroticket_api.notification.NotificationDispatcher notifier;

    public PaymentOrderService(PaymentOrderRepository orders, SepayTransactionRepository sepayTransactions,
            PaymentService payments, PaymentProps props, List<PaymentFulfiller> fulfillerBeans, Clock clock,
            com.metroticket_api.notification.NotificationDispatcher notifier) {
        this.notifier = notifier;
        this.orders = orders;
        this.sepayTransactions = sepayTransactions;
        this.payments = payments;
        this.sepay = props.sepay();
        this.clock = clock;
        fulfillerBeans.forEach(f -> fulfillers.put(f.purpose(), f));
        this.codePattern = Pattern.compile(Pattern.quote(sepay.codePrefix().toUpperCase(Locale.ROOT))
                + "[A-Z0-9]{" + CODE_SUFFIX_LENGTH + "}");
    }

    // ===================== Tạo đơn / xem / huỷ =====================

    /** Gọi trong transaction mua vé: tạo đơn chờ thanh toán cho hàng hoá {@code referenceId}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentOrder createOrder(UUID userId, PaymentPurpose purpose, String referenceId, int amountVnd) {
        if (!sepay.enabled() || sepay.accountNumber().isBlank() || sepay.bank().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Thanh toán QR chưa được cấu hình");
        }
        if (amountVnd <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số tiền thanh toán phải lớn hơn 0");
        }
        if (orders.countByUserIdAndStatus(userId, PaymentOrderStatus.PENDING) >= sepay.maxPendingOrdersPerUser()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đang có quá nhiều đơn chưa thanh toán, hãy thanh toán hoặc huỷ bớt");
        }
        String code;
        do {
            code = SecureCodes.humanCode(sepay.codePrefix().toUpperCase(Locale.ROOT), CODE_SUFFIX_LENGTH);
        } while (orders.existsByOrderCode(code));
        return orders.save(new PaymentOrder(code, userId, purpose, referenceId, amountVnd,
                clock.instant().plus(sepay.orderTtl())));
    }

    public PaymentInstruction instructionOf(PaymentOrder order) {
        String url = sepay.qrImageUrl()
                + "?acc=" + encode(sepay.accountNumber())
                + "&bank=" + encode(sepay.bank())
                + "&amount=" + order.getAmountVnd()
                + "&des=" + encode(order.getOrderCode());
        return new PaymentInstruction(order.getOrderCode(), order.getAmountVnd(), order.getStatus(),
                order.getExpiresAt(), sepay.bank(), sepay.accountNumber(), sepay.accountHolder(), order.getOrderCode(),
                order.getStatus() == PaymentOrderStatus.PENDING ? url : null, order.getPaidAt());
    }

    @Transactional(readOnly = true)
    public Optional<PaymentInstruction> instructionFor(PaymentPurpose purpose, String referenceId) {
        return orders.findByPurposeAndReferenceId(purpose, referenceId).map(this::instructionOf);
    }

    /** App gọi định kỳ (polling) để biết đơn đã thanh toán chưa. Chỉ chủ đơn xem được. */
    @Transactional(readOnly = true)
    public PaymentInstruction getMine(String orderCode) {
        return instructionOf(requireMine(orders.findByOrderCode(normalize(orderCode))));
    }

    @Transactional
    public PaymentInstruction cancelMine(String orderCode) {
        PaymentOrder order = requireMine(orders.findByOrderCodeForUpdate(normalize(orderCode)));
        if (order.getStatus() != PaymentOrderStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ huỷ được đơn đang chờ thanh toán");
        }
        order.cancel();
        fulfiller(order).release(order);
        return instructionOf(order);
    }

    // ===================== Webhook =====================

    /** Xử lý idempotent. Mọi kết quả (kể cả không khớp) đều trả về bình thường để SePay không gửi lại. */
    @Transactional
    public SepayResult handleWebhook(SepayWebhookPayload payload) {
        if (sepayTransactions.existsById(payload.id())) {
            return sepayTransactions.findById(payload.id()).map(SepayTransaction::getResult).orElse(SepayResult.IGNORED);
        }
        Instant now = clock.instant();
        SepayTransaction tx = sepayTransactions.saveAndFlush(new SepayTransaction(payload, now));

        if (!payload.incoming()) {
            tx.resolve(SepayResult.IGNORED, null, "Giao dịch tiền ra");
            return tx.getResult();
        }
        if (payload.accountNumber() != null && !payload.accountNumber().equals(sepay.accountNumber())) {
            tx.resolve(SepayResult.IGNORED, null, "Khác tài khoản thụ hưởng " + sepay.accountNumber());
            return tx.getResult();
        }
        Optional<String> code = extractCode(payload);
        Optional<PaymentOrder> found = code.flatMap(orders::findByOrderCodeForUpdate);
        if (found.isEmpty()) {
            tx.resolve(SepayResult.UNMATCHED, null, code.map(c -> "Không có đơn " + c).orElse("Không thấy mã đơn trong nội dung"));
            log.warn("SePay {}: không khớp đơn (content='{}')", payload.id(), payload.content());
            return tx.getResult();
        }
        PaymentOrder order = found.get();
        long amount = payload.transferAmount();

        if (order.getStatus() == PaymentOrderStatus.PAID || order.getStatus() == PaymentOrderStatus.CANCELLED) {
            tx.resolve(SepayResult.NEEDS_REFUND, order.getId(), order.getStatus() == PaymentOrderStatus.PAID
                    ? "Đơn đã được thanh toán trước đó (chuyển trùng)" : "Đơn đã bị khách huỷ");
            return tx.getResult();
        }
        if (amount < order.getAmountVnd()) {
            tx.resolve(SepayResult.AMOUNT_MISMATCH, order.getId(),
                    "Chuyển " + amount + " đ, cần " + order.getAmountVnd() + " đ");
            return tx.getResult();
        }

        boolean late = order.getStatus() == PaymentOrderStatus.EXPIRED;
        int paid = Math.toIntExact(amount);
        order.markPaid(paid, payload.id(), now);
        payments.recordSepay(order.getUserId(), order.getPurpose(), order.getReferenceId(), paid, payload.id(),
                payload.referenceCode());
        String failure = fulfiller(order).fulfil(order, late, now);
        if (failure != null) {
            payments.refundAll(order.getPurpose(), order.getReferenceId());
            tx.resolve(SepayResult.NEEDS_REFUND, order.getId(), failure);
            notifyPayment(order, "Đã nhận " + paid + " đ nhưng không kích hoạt được",
                    "Đơn " + order.getOrderCode() + ": " + failure + ". Chúng tôi sẽ hoàn tiền cho bạn.", "PAYMENT_REFUND:");
            return tx.getResult();
        }
        notifyPayment(order, "Thanh toán thành công", "Đã nhận " + paid + " đ cho đơn " + order.getOrderCode()
                + (order.getPurpose() == PaymentPurpose.TICKET ? ". Vé của bạn đã sẵn sàng." : ". Vé tháng gửi xe đã được kích hoạt."),
                "PAYMENT_PAID:");
        String note = (late ? "Thanh toán sau khi đơn hết hạn. " : "")
                + (amount > order.getAmountVnd() ? "Chuyển thừa " + (amount - order.getAmountVnd()) + " đ" : "");
        tx.resolve(SepayResult.MATCHED, order.getId(), note.isBlank() ? null : note.trim());
        return tx.getResult();
    }

    /** Ưu tiên field {@code code} SePay đã tách; ngân hàng có thể chèn ký tự nên dò lại trong content. */
    Optional<String> extractCode(SepayWebhookPayload payload) {
        for (String candidate : new String[] { payload.code(), payload.content(), payload.description() }) {
            if (candidate == null) {
                continue;
            }
            Matcher m = codePattern.matcher(candidate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", " "));
            if (m.find()) {
                return Optional.of(m.group());
            }
            Matcher compact = codePattern.matcher(candidate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
            if (compact.find()) {
                return Optional.of(compact.group());
            }
        }
        return Optional.empty();
    }

    // ===================== Hết hạn =====================

    @Scheduled(fixedDelayString = "${app.payment.expiry-job-interval:1m}", initialDelayString = "${app.payment.expiry-job-interval:1m}")
    @Transactional
    public void expireOrders() {
        List<PaymentOrder> expired = orders.findExpiredPendingForUpdate(clock.instant());
        for (PaymentOrder order : expired) {
            order.expire();
            fulfiller(order).release(order);
            notifyPayment(order, "Đơn thanh toán đã hết hạn", "Đơn " + order.getOrderCode()
                    + " chưa nhận được tiền nên đã huỷ. Nếu bạn đã chuyển khoản, hệ thống vẫn tự kích hoạt khi tiền về.",
                    "PAYMENT_EXPIRED:");
        }
        if (!expired.isEmpty()) {
            log.info("Đã huỷ giữ chỗ {} đơn thanh toán quá hạn", expired.size());
        }
    }

    private void notifyPayment(PaymentOrder order, String title, String body, String keyPrefix) {
        String link = order.getPurpose() == PaymentPurpose.TICKET ? "/tickets/" + order.getReferenceId()
                : "/parking/passes/" + order.getReferenceId();
        notifier.dispatch(com.metroticket_api.notification.NotificationDispatcher.Request
                .of(order.getUserId(), com.metroticket_api.notification.NotificationType.PAYMENT, title, body)
                .link(link).dedupe(keyPrefix + order.getId()));
    }

    private PaymentFulfiller fulfiller(PaymentOrder order) {
        PaymentFulfiller f = fulfillers.get(order.getPurpose());
        if (f == null) {
            throw new IllegalStateException("Chưa có PaymentFulfiller cho " + order.getPurpose());
        }
        return f;
    }

    private static PaymentOrder requireMine(Optional<PaymentOrder> found) {
        UUID me = CurrentUser.id();
        return found.filter(o -> o.getUserId().equals(me))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn thanh toán"));
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
