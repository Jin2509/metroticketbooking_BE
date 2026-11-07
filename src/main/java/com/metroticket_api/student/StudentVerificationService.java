package com.metroticket_api.student;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.ai.GeminiProps;
import com.metroticket_api.ai.StudentCardExtractor;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.student.StudentDtos.AdminVerificationResponse;
import com.metroticket_api.student.StudentDtos.MyVerificationResponse;
import com.metroticket_api.student.StudentDtos.StudentStatusResponse;
import com.metroticket_api.student.StudentVerification.DecisionSource;
import com.metroticket_api.ticket.TicketProductRepository;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;

/**
 * Xác thực sinh viên. Nộp hồ sơ → AI (Gemini) đọc thẻ ở nền → áp điều kiện → tự duyệt / tự từ chối / chờ duyệt tay.
 * Sinh viên nhận kết quả qua thông báo (in-app + email). ADMIN/MANAGER (STUDENT_VERIFY) duyệt tay hàng chờ.
 */
@Service
public class StudentVerificationService {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    static final int MIN_IMAGE_BYTES = 10 * 1024;
    /** Còn ưu đãi lâu hơn mốc này thì không cho nộp lại (tránh spam AI). */
    static final Duration RENEW_WINDOW = Duration.ofDays(30);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StudentVerificationRepository verifications;
    private final StudentVerificationImageRepository images;
    private final StudentPolicyRepository policies;
    private final UserRepository users;
    private final TicketProductRepository products;
    private final StudentCardExtractor extractor;
    private final GeminiProps geminiProps;
    private final com.metroticket_api.notification.NotificationDispatcher dispatcher;
    private final Clock clock;

    public StudentVerificationService(StudentVerificationRepository verifications,
            StudentVerificationImageRepository images, StudentPolicyRepository policies, UserRepository users,
            TicketProductRepository products, StudentCardExtractor extractor, GeminiProps geminiProps,
            com.metroticket_api.notification.NotificationDispatcher dispatcher, Clock clock) {
        this.verifications = verifications;
        this.images = images;
        this.policies = policies;
        this.users = users;
        this.products = products;
        this.extractor = extractor;
        this.geminiProps = geminiProps;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    // ===================== Sinh viên =====================

    @Transactional
    public MyVerificationResponse submit(String fullName, String studentCode, String school, boolean consent,
            byte[] image) {
        UUID userId = CurrentUser.id();
        StudentPolicy policy = policies.current();
        if (!policy.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chức năng xác thực sinh viên đang tạm dừng");
        }
        if (!consent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cần đồng ý cho hệ thống (và dịch vụ AI) xử lý ảnh thẻ sinh viên để xác thực");
        }
        String mime = detectImageType(image);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, VIETNAM);
        if (verifications.existsByUserIdAndStatusIn(userId, List.of(StudentVerificationStatus.PENDING,
                StudentVerificationStatus.PROCESSING, StudentVerificationStatus.NEEDS_REVIEW))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bạn đang có hồ sơ chờ xác thực, vui lòng đợi kết quả");
        }
        Optional<StudentVerification> active = verifications.findActiveApproval(userId, today).stream().findFirst();
        if (active.isPresent() && active.get().getApprovedUntil().isAfter(today.plusDays(RENEW_WINDOW.toDays()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Bạn đã được xác thực sinh viên đến " + DATE.format(active.get().getApprovedUntil()));
        }
        if (verifications.countByUserIdAndSubmittedAtAfter(userId, now.minus(Duration.ofDays(30)))
                >= policy.getMaxSubmissionsPer30Days()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã nộp quá " + policy.getMaxSubmissionsPer30Days() + " hồ sơ trong 30 ngày");
        }
        StudentVerification v = new StudentVerification(userId, fullName.trim(), studentCode.trim(), school.trim(), mime,
                now, extractor.available());
        verifications.saveAndFlush(v);
        images.save(new StudentVerificationImage(v.getId(), image));
        return mine(v);
    }

    @Transactional(readOnly = true)
    public StudentStatusResponse myStatus() {
        UUID userId = CurrentUser.id();
        StudentPolicy policy = policies.current();
        LocalDate today = LocalDate.ofInstant(clock.instant(), VIETNAM);
        Optional<StudentVerification> active = verifications.findActiveApproval(userId, today).stream().findFirst();
        MyVerificationResponse latest = verifications.findFirstByUserIdOrderBySubmittedAtDesc(userId).map(this::mine)
                .orElse(null);
        return new StudentStatusResponse(active.isPresent(), active.map(StudentVerification::getApprovedUntil).orElse(null),
                policy.getDiscountPercent(), policy.eligibleProductCodeList(), latest);
    }

    @Transactional(readOnly = true)
    public List<MyVerificationResponse> myHistory() {
        return verifications.findByUserIdOrderBySubmittedAtDesc(CurrentUser.id()).stream().map(this::mine).toList();
    }

    @Transactional
    public MyVerificationResponse cancel(UUID id) {
        StudentVerification v = verifications.findById(id).filter(x -> x.getUserId().equals(CurrentUser.id()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ"));
        if (v.getStatus() == StudentVerificationStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hồ sơ đang được AI xử lý, vui lòng thử lại sau ít phút");
        }
        if (!v.getStatus().open()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hồ sơ đã có kết quả, không huỷ được");
        }
        v.cancel(clock.instant());
        return mine(v);
    }

    // ===================== Ưu đãi =====================

    public record StudentDiscount(int percent, LocalDate validUntil) {
    }

    /** Ưu đãi sinh viên cho loại vé, nếu người dùng đang được xác thực và loại vé nằm trong danh sách áp dụng. */
    @Transactional(readOnly = true)
    public Optional<StudentDiscount> discountFor(UUID userId, String productCode) {
        StudentPolicy policy = policies.current();
        if (!policy.isEnabled() || policy.getDiscountPercent() <= 0
                || !policy.eligibleProductCodeList().contains(productCode.toUpperCase())) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), VIETNAM);
        return verifications.findActiveApproval(userId, today).stream().findFirst()
                .map(v -> new StudentDiscount(policy.getDiscountPercent(), v.getApprovedUntil()));
    }

    // ===================== Duyệt tay (ADMIN / MANAGER) =====================

    @Transactional(readOnly = true)
    public Page<AdminVerificationResponse> list(StudentVerificationStatus status, Pageable pageable) {
        Page<StudentVerification> page = status == null ? verifications.findAllByOrderBySubmittedAtDesc(pageable)
                : verifications.findByStatusOrderBySubmittedAtAsc(status, pageable);
        Map<UUID, User> byId = users.findAllById(page.map(StudentVerification::getUserId).toSet()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return page.map(v -> admin(v, byId.get(v.getUserId())));
    }

    @Transactional(readOnly = true)
    public AdminVerificationResponse get(UUID id) {
        StudentVerification v = require(id);
        return admin(v, users.findById(v.getUserId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public StudentVerificationImage image(UUID id) {
        StudentVerification v = require(id);
        return images.findById(v.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE,
                "Ảnh thẻ đã được xoá theo chính sách lưu giữ dữ liệu"));
    }

    /** Duyệt: từ hàng chờ duyệt tay, hồ sơ AI chưa xử lý được, hoặc lật lại quyết định từ chối của AI (khiếu nại). */
    @Transactional
    public AdminVerificationResponse approve(UUID id, LocalDate requestedUntil, String note) {
        StudentVerification v = require(id);
        UUID reviewer = requireReviewer(v);
        boolean overturnAi = v.getStatus() == StudentVerificationStatus.REJECTED && v.getDecisionSource() == DecisionSource.AI;
        if (!(v.getStatus() == StudentVerificationStatus.NEEDS_REVIEW || v.getStatus() == StudentVerificationStatus.PENDING
                || overturnAi)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể duyệt hồ sơ ở trạng thái " + v.getStatus());
        }
        StudentPolicy policy = policies.current();
        LocalDate today = LocalDate.ofInstant(clock.instant(), VIETNAM);
        LocalDate until = requestedUntil != null ? requestedUntil
                : StudentPolicyEvaluator.approvedUntil(policy, v.getAiValidUntil(), today);
        if (!until.isAfter(today) || until.isAfter(today.plusMonths(policy.getMaxValidityMonths()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hạn ưu đãi phải sau hôm nay và không quá " + policy.getMaxValidityMonths() + " tháng");
        }
        v.approve(DecisionSource.MANUAL, until, "Duyệt tay" + (overturnAi ? " (lật lại quyết định của AI)" : ""),
                reviewer, blankToNull(note), clock.instant());
        notifyApproved(v, policy);
        return get(id);
    }

    @Transactional
    public AdminVerificationResponse reject(UUID id, String reason) {
        StudentVerification v = require(id);
        UUID reviewer = requireReviewer(v);
        if (v.getStatus() != StudentVerificationStatus.NEEDS_REVIEW && v.getStatus() != StudentVerificationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể từ chối hồ sơ ở trạng thái " + v.getStatus());
        }
        v.reject(DecisionSource.MANUAL, "Từ chối khi duyệt tay", reviewer, reason.trim(), clock.instant());
        notifyRejected(v, reason.trim());
        return get(id);
    }

    /** Thu hồi ưu đãi đã duyệt (phát hiện gian lận, thôi học). Vé đã mua trước đó giữ nguyên. */
    @Transactional
    public AdminVerificationResponse revoke(UUID id, String reason) {
        StudentVerification v = require(id);
        UUID reviewer = requireReviewer(v);
        if (v.getStatus() != StudentVerificationStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ thu hồi được hồ sơ đã duyệt");
        }
        v.revoke(reviewer, reason.trim(), clock.instant());
        notifyUser(v, "Ưu đãi sinh viên đã bị thu hồi", "Lý do: " + reason.trim()
                + ". Nếu có nhầm lẫn, vui lòng nộp lại hồ sơ hoặc liên hệ quầy hỗ trợ.");
        return get(id);
    }

    /** Đưa hồ sơ chờ duyệt tay về cho AI đọc lại (ví dụ sau khi cấu hình API key hoặc đổi model). */
    @Transactional
    public AdminVerificationResponse retryAi(UUID id) {
        StudentVerification v = require(id);
        if (v.getStatus() != StudentVerificationStatus.NEEDS_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ gửi lại AI cho hồ sơ đang chờ duyệt tay");
        }
        if (!extractor.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI chưa được cấu hình");
        }
        if (!images.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.GONE, "Ảnh thẻ đã bị xoá");
        }
        v.retryLater(null, clock.instant());
        return get(id);
    }

    /** Xoá ảnh thẻ (dữ liệu cá nhân) sau image_retention_days kể từ khi có quyết định. */
    @Scheduled(cron = "${app.student.image-purge-cron:0 30 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public int purgeImages() {
        Instant now = clock.instant();
        Instant before = now.minus(Duration.ofDays(policies.current().getImageRetentionDays()));
        int purged = 0;
        for (StudentVerification v : verifications.findImagesToPurge(before)) {
            images.deleteById(v.getId());
            v.markImagePurged(now);
            purged++;
        }
        return purged;
    }

    // ===================== Thông báo (dùng chung với worker) =====================

    void notifyApproved(StudentVerification v, StudentPolicy policy) {
        notifyUser(v, "Xác thực sinh viên thành công",
                "Bạn được giảm " + policy.getDiscountPercent() + "% cho vé " + String.join(", ", policy.eligibleProductCodeList())
                        + " đến hết ngày " + DATE.format(v.getApprovedUntil()) + ".");
    }

    void notifyRejected(StudentVerification v, String reason) {
        notifyUser(v, "Xác thực sinh viên không thành công", reason
                + ". Bạn có thể nộp lại ảnh thẻ rõ nét, đúng thông tin.");
    }

    void notifyReview(StudentVerification v) {
        notifyUser(v, "Hồ sơ sinh viên đang chờ duyệt",
                "Hệ thống cần nhân viên kiểm tra thêm hồ sơ của bạn. Kết quả sẽ được gửi trong 1–2 ngày làm việc.");
    }

    private void notifyUser(StudentVerification v, String title, String body) {
        dispatcher.dispatch(com.metroticket_api.notification.NotificationDispatcher.Request
                .of(v.getUserId(), NotificationType.STUDENT, title, body)
                .link("/student").withEmail().dedupe("STUDENT:" + v.getId() + ":" + v.getStatus()));
    }

    // ===================== Helpers =====================

    private StudentVerification require(UUID id) {
        return verifications.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ"));
    }

    /** Người duyệt phải đăng nhập và không được tự duyệt hồ sơ của chính mình. */
    private static UUID requireReviewer(StudentVerification v) {
        UUID reviewer = CurrentUser.id();
        if (Objects.equals(reviewer, v.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không được tự duyệt hồ sơ sinh viên của chính mình");
        }
        return reviewer;
    }

    static String detectImageType(byte[] b) {
        if (b == null || b.length < MIN_IMAGE_BYTES || b.length > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ảnh thẻ phải từ 10 KB đến 5 MB");
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ nhận ảnh JPEG, PNG hoặc WebP");
    }

    private MyVerificationResponse mine(StudentVerification v) {
        String message = switch (v.getStatus()) {
            case PENDING, PROCESSING -> "Đang xác thực tự động, vui lòng đợi (thường vài phút)";
            case NEEDS_REVIEW -> "Đang chờ nhân viên duyệt";
            case APPROVED -> "Đã xác thực đến " + DATE.format(v.getApprovedUntil());
            case REJECTED -> v.getDecisionSource() == DecisionSource.MANUAL && v.getReviewNote() != null
                    ? "Không được duyệt: " + v.getReviewNote() : "Không được duyệt: " + v.getDecisionReasons();
            case CANCELLED -> "Đã huỷ";
            case REVOKED -> "Ưu đãi đã bị thu hồi" + (v.getReviewNote() == null ? "" : ": " + v.getReviewNote());
        };
        return new MyVerificationResponse(v.getId(), v.getStatus(), v.getDeclaredFullName(), v.getDeclaredStudentCode(),
                v.getDeclaredSchool(), v.getSubmittedAt(), v.getDecidedAt(), v.getApprovedUntil(), message);
    }

    private AdminVerificationResponse admin(StudentVerification v, User user) {
        return new AdminVerificationResponse(v.getId(), v.getUserId(), user == null ? null : user.getEmail(),
                user == null ? null : user.getFullName(), v.getStatus(), v.getDeclaredFullName(),
                v.getDeclaredStudentCode(), v.getDeclaredSchool(), v.getAiAttempts(), v.getAiModel(),
                v.getAiIsStudentCard(), v.getAiFullName(), v.getAiStudentCode(), v.getAiSchool(), v.getAiValidUntil(),
                v.getAiConfidence(), v.getAiTamperingSuspected(), v.getAiNotes(), v.getAiError(), v.getAiCompletedAt(),
                v.getDecisionSource(), v.getDecisionReasons(), v.getReviewedBy(), v.getReviewNote(), v.getApprovedUntil(),
                v.getSubmittedAt(), v.getDecidedAt(), v.getImagePurgedAt() == null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // dùng bởi StudentPolicyService
    boolean aiConfigured() {
        return extractor.available();
    }

    String aiModel() {
        return geminiProps.model();
    }

    boolean productExists(String code) {
        return products.existsByCode(code);
    }
}
