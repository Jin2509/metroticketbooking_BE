package com.metroticket_api.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.metroticket_api.ai.StudentCardExtraction;
import com.metroticket_api.student.StudentPolicyEvaluator.Declared;
import com.metroticket_api.student.StudentPolicyEvaluator.Outcome;

class StudentPolicyEvaluatorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 8);
    static final Declared DECLARED = new Declared("Nguyễn Văn An", "SV2022-001", "Đại học Bách khoa", "Nguyễn Văn An");

    static StudentCardExtraction card(boolean student, String name, String code, String school, LocalDate until,
            String confidence, boolean tampered) {
        return new StudentCardExtraction(student, name, code, school, until, new BigDecimal(confidence), tampered, null,
                "gemini-test");
    }

    static StudentCardExtraction good() {
        return card(true, "NGUYEN VAN AN", "SV2022001", "Trường Đại học Bách khoa - ĐHQG TP.HCM",
                LocalDate.of(2027, 9, 30), "0.95", false);
    }

    @Test
    void approvesWhenEveryConditionPassesAndCapsValidity() {
        var d = StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED, good(), TODAY);
        assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(d.approvedUntil()).as("theo hạn thẻ").isEqualTo(LocalDate.of(2027, 9, 30));
        var longCard = card(true, "NGUYEN VAN AN", "SV2022001", "BK", LocalDate.of(2030, 9, 30), "0.95", false);
        assertThat(StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED, longCard, TODAY).approvedUntil())
                .as("thẻ hạn 2030 nhưng tối đa 12 tháng").isEqualTo(TODAY.plusMonths(12));
    }

    @Test
    void nameComparisonIgnoresAccentsCaseAndPunctuation() {
        assertThat(StudentPolicyEvaluator.sameName("Trần Thị Đào", "TRAN THI DAO")).isTrue();
        assertThat(StudentPolicyEvaluator.sameName("Trần Thị Đào", "Trần Thị Hoa")).isFalse();
        assertThat(StudentPolicyEvaluator.sameCode("SV-2022 001", "sv2022001")).isTrue();
    }

    @Test
    void uncertainCasesGoToManualReviewNeverAutoReject() {
        assertThat(StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED,
                card(true, "NGUYEN VAN AN", "SV2022001", "BK", LocalDate.of(2027, 1, 1), "0.60", false), TODAY).outcome())
                .as("confidence thấp").isEqualTo(Outcome.REVIEW);
        assertThat(StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED,
                card(true, "NGUYEN VAN AN", "SV2022001", "BK", LocalDate.of(2027, 1, 1), "0.95", true), TODAY).outcome())
                .as("nghi chỉnh sửa").isEqualTo(Outcome.REVIEW);
        assertThat(StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED,
                card(true, "LE THI BINH", "SV2022001", "BK", LocalDate.of(2027, 1, 1), "0.95", false), TODAY).outcome())
                .as("lệch tên").isEqualTo(Outcome.REVIEW);
        assertThat(StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED,
                card(false, null, null, null, null, "0.40", false), TODAY).outcome())
                .as("không chắc có phải thẻ").isEqualTo(Outcome.REVIEW);
    }

    @Test
    void clearFailuresAreRejected() {
        assertThat(StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED,
                card(false, null, null, null, null, "0.97", false), TODAY).outcome())
                .as("chắc chắn không phải thẻ SV").isEqualTo(Outcome.REJECT);
        var expired = StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED,
                card(true, "NGUYEN VAN AN", "SV2022001", "BK", LocalDate.of(2026, 9, 30), "0.95", false), TODAY);
        assertThat(expired.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(expired.reasons()).anyMatch(r -> r.contains("hết hạn"));
    }

    @Test
    void acceptedSchoolsListIsEnforced() {
        StudentPolicy policy = new StudentPolicy();
        policy.setAcceptedSchools(List.of("Đại học Quốc gia TP.HCM", "Bách khoa"));
        assertThat(StudentPolicyEvaluator.evaluate(policy, DECLARED, good(), TODAY).outcome()).isEqualTo(Outcome.APPROVE);
        policy.setAcceptedSchools(List.of("Đại học Kinh tế"));
        assertThat(StudentPolicyEvaluator.evaluate(policy, DECLARED, good(), TODAY).outcome()).isEqualTo(Outcome.REJECT);
    }

    @Test
    void autoApproveOffSendsEverythingToReview() {
        StudentPolicy policy = new StudentPolicy();
        policy.setAutoApprove(false);
        assertThat(StudentPolicyEvaluator.evaluate(policy, DECLARED, good(), TODAY).outcome()).isEqualTo(Outcome.REVIEW);
    }

    @Test
    void declaredNameMustMatchAccount() {
        Declared other = new Declared("Nguyễn Văn An", "SV2022001", "BK", "Phạm Minh Tuấn");
        assertThat(StudentPolicyEvaluator.evaluate(new StudentPolicy(), other, good(), TODAY).outcome())
                .isEqualTo(Outcome.REVIEW);
    }

    @Test
    void cardWithoutExpiryUsesDefaultValidity() {
        var d = StudentPolicyEvaluator.evaluate(new StudentPolicy(), DECLARED,
                card(true, "NGUYEN VAN AN", "SV2022001", "BK", null, "0.95", false), TODAY);
        assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(d.approvedUntil()).isEqualTo(TODAY.plusMonths(12));
    }
}
