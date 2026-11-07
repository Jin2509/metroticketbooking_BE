package com.metroticket_api.student;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.ai.GeminiProps;
import com.metroticket_api.ai.StudentCardExtraction;
import com.metroticket_api.student.StudentPolicyEvaluator.Decision;
import com.metroticket_api.student.StudentPolicyEvaluator.Declared;
import com.metroticket_api.student.StudentVerification.DecisionSource;
import com.metroticket_api.user.UserRepository;

/**
 * Các bước có transaction của worker (tách bean để @Transactional chạy qua proxy). Lời gọi AI nằm NGOÀI transaction
 * để không giữ kết nối DB hàng chục giây.
 */
@Component
public class StudentVerificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(StudentVerificationProcessor.class);
    /** PROCESSING quá mốc này (worker chết, restart) thì trả về hàng đợi. */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    public record Job(UUID id, byte[] image, String mimeType, int attempts) {
    }

    private final StudentVerificationRepository verifications;
    private final StudentVerificationImageRepository images;
    private final StudentPolicyRepository policies;
    private final UserRepository users;
    private final StudentVerificationService service;
    private final GeminiProps gemini;
    private final Clock clock;

    public StudentVerificationProcessor(StudentVerificationRepository verifications,
            StudentVerificationImageRepository images, StudentPolicyRepository policies, UserRepository users,
            StudentVerificationService service, GeminiProps gemini, Clock clock) {
        this.verifications = verifications;
        this.images = images;
        this.policies = policies;
        this.users = users;
        this.service = service;
        this.gemini = gemini;
        this.clock = clock;
    }

    @Transactional
    public List<UUID> claim(int limit) {
        Instant now = clock.instant();
        int released = verifications.releaseStale(now.minus(STALE_AFTER), now);
        if (released > 0) {
            log.warn("Trả {} hồ sơ sinh viên PROCESSING quá hạn về hàng đợi", released);
        }
        List<UUID> ids = verifications.lockDue(now, limit);
        if (!ids.isEmpty()) {
            verifications.markProcessing(ids, now);
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public Job load(UUID id) {
        StudentVerification v = verifications.findById(id).orElse(null);
        if (v == null || v.getStatus() != StudentVerificationStatus.PROCESSING) {
            return null;
        }
        byte[] image = images.findById(id).map(StudentVerificationImage::getContent).orElse(null);
        return new Job(id, image, v.getImageMimeType(), v.getAiAttempts());
    }

    /** Executor đầy: trả hồ sơ về hàng đợi ngay, không tính là một lần thử. */
    @Transactional
    public void requeue(UUID id) {
        verifications.findById(id).filter(v -> v.getStatus() == StudentVerificationStatus.PROCESSING)
                .ifPresent(v -> v.retryLater(v.getAiError(), clock.instant().plusSeconds(10)));
    }

    @Transactional
    public void complete(UUID id, StudentCardExtraction ai) {
        StudentVerification v = verifications.findById(id).orElse(null);
        if (v == null || v.getStatus() != StudentVerificationStatus.PROCESSING) {
            return;
        }
        Instant now = clock.instant();
        v.recordAi(ai, now);
        StudentPolicy policy = policies.current();
        String accountName = users.findById(v.getUserId()).map(u -> u.getFullName()).orElse(null);
        Decision decision = StudentPolicyEvaluator.evaluate(policy,
                new Declared(v.getDeclaredFullName(), v.getDeclaredStudentCode(), v.getDeclaredSchool(), accountName),
                ai, LocalDate.ofInstant(now, StudentVerificationService.VIETNAM));
        String reasons = String.join("; ", decision.reasons());
        switch (decision.outcome()) {
            case APPROVE -> {
                v.approve(DecisionSource.AI, decision.approvedUntil(), reasons, null, null, now);
                service.notifyApproved(v, policy);
            }
            case REJECT -> {
                v.reject(DecisionSource.AI, reasons, null, null, now);
                service.notifyRejected(v, reasons);
            }
            case REVIEW -> {
                v.needsReview(reasons, null);
                service.notifyReview(v);
            }
        }
        log.info("Hồ sơ sinh viên {} → {} ({})", id, v.getStatus(), reasons);
    }

    /** Lỗi tạm thời còn lượt thử → thử lại sau (backoff tăng dần); hết lượt hoặc lỗi cố định → chờ duyệt tay. */
    @Transactional
    public void fail(UUID id, String error, boolean retryable) {
        StudentVerification v = verifications.findById(id).orElse(null);
        if (v == null || v.getStatus() != StudentVerificationStatus.PROCESSING) {
            return;
        }
        if (retryable && v.getAiAttempts() < gemini.maxAttempts()) {
            v.retryLater(error, clock.instant().plus(gemini.retryBackoff().multipliedBy(v.getAiAttempts())));
            return;
        }
        v.needsReview("AI không xử lý được hồ sơ, chuyển duyệt tay", error);
        service.notifyReview(v);
    }
}
