package com.metroticket_api.student;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.student.StudentDtos.PolicyResponse;
import com.metroticket_api.student.StudentDtos.UpdatePolicyRequest;

/** Điều kiện xác thực sinh viên do admin nhập (STUDENT_POLICY_MANAGE). */
@Service
public class StudentPolicyService {

    private final StudentPolicyRepository policies;
    private final StudentVerificationService verifications;
    private final Clock clock;

    public StudentPolicyService(StudentPolicyRepository policies, StudentVerificationService verifications, Clock clock) {
        this.policies = policies;
        this.verifications = verifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PolicyResponse get() {
        return PolicyResponse.of(policies.current(), verifications.aiConfigured(), verifications.aiModel());
    }

    @Transactional
    public PolicyResponse update(UpdatePolicyRequest r) {
        StudentPolicy p = policies.findById((short) 1)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Thiếu dòng cấu hình (V18)"));
        if (r.version() != p.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cấu hình vừa được người khác thay đổi, hãy tải lại");
        }
        if (r.enabled() != null) p.setEnabled(r.enabled());
        if (r.autoApprove() != null) p.setAutoApprove(r.autoApprove());
        if (r.minConfidence() != null) p.setMinConfidence(r.minConfidence());
        if (r.acceptedSchools() != null) p.setAcceptedSchools(r.acceptedSchools().stream().map(String::trim).distinct().toList());
        if (r.requireNameMatch() != null) p.setRequireNameMatch(r.requireNameMatch());
        if (r.requireAccountNameMatch() != null) p.setRequireAccountNameMatch(r.requireAccountNameMatch());
        if (r.requireStudentCodeMatch() != null) p.setRequireStudentCodeMatch(r.requireStudentCodeMatch());
        if (r.requireNotExpired() != null) p.setRequireNotExpired(r.requireNotExpired());
        if (r.requireValidUntil() != null) p.setRequireValidUntil(r.requireValidUntil());
        if (r.defaultValidityMonths() != null) p.setDefaultValidityMonths(r.defaultValidityMonths());
        if (r.maxValidityMonths() != null) p.setMaxValidityMonths(r.maxValidityMonths());
        if (r.discountPercent() != null) p.setDiscountPercent(r.discountPercent());
        if (r.eligibleProductCodes() != null) {
            List<String> codes = r.eligibleProductCodes().stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).distinct().toList();
            for (String code : codes) {
                if (!verifications.productExists(code)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có loại vé " + code);
                }
            }
            p.setEligibleProductCodes(codes);
        }
        if (r.maxSubmissionsPer30Days() != null) p.setMaxSubmissionsPer30Days(r.maxSubmissionsPer30Days());
        if (r.imageRetentionDays() != null) p.setImageRetentionDays(r.imageRetentionDays());
        if (p.getDefaultValidityMonths() > p.getMaxValidityMonths()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "defaultValidityMonths không được lớn hơn maxValidityMonths");
        }
        p.touch(CurrentUser.optionalId().orElse(null), clock.instant());
        policies.saveAndFlush(p);
        return get();
    }
}
