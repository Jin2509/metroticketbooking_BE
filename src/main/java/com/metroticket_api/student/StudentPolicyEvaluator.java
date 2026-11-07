package com.metroticket_api.student;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.metroticket_api.ai.StudentCardExtraction;

/**
 * Áp điều kiện của admin lên kết quả AI. Nguyên tắc an toàn:
 * <ul>
 *   <li>Chỉ TỰ TỪ CHỐI khi chắc chắn: AI tin chắc ảnh không phải thẻ sinh viên, thẻ đã hết hạn, hoặc trường không
 *       thuộc danh sách chấp nhận.</li>
 *   <li>Mọi trường hợp lưỡng lự (confidence thấp, nghi chỉnh sửa, lệch tên/mã, thiếu ngày hết hạn) → DUYỆT TAY.</li>
 *   <li>Chỉ TỰ DUYỆT khi bật auto_approve và mọi điều kiện đều đạt.</li>
 * </ul>
 */
public final class StudentPolicyEvaluator {

    public enum Outcome { APPROVE, REJECT, REVIEW }

    public record Declared(String fullName, String studentCode, String school, String accountFullName) {
    }

    public record Decision(Outcome outcome, List<String> reasons, LocalDate approvedUntil) {
    }

    private StudentPolicyEvaluator() {
    }

    public static Decision evaluate(StudentPolicy policy, Declared declared, StudentCardExtraction ai, LocalDate today) {
        List<String> reject = new ArrayList<>();
        List<String> review = new ArrayList<>();
        boolean confident = ai.confidence() != null && ai.confidence().compareTo(policy.getMinConfidence()) >= 0;

        if (!ai.studentCard()) {
            (confident ? reject : review).add("Ảnh không phải thẻ sinh viên" + note(ai));
        }
        if (ai.tamperingSuspected()) {
            review.add("Nghi ngờ ảnh bị chỉnh sửa hoặc chụp lại" + note(ai));
        }
        if (!confident) {
            review.add("Độ tin cậy " + ai.confidence() + " thấp hơn ngưỡng " + policy.getMinConfidence());
        }
        if (ai.validUntil() == null) {
            if (policy.isRequireValidUntil()) {
                review.add("Không đọc được ngày hết hạn trên thẻ");
            }
        } else if (policy.isRequireNotExpired() && ai.validUntil().isBefore(today)) {
            reject.add("Thẻ đã hết hạn ngày " + ai.validUntil());
        }
        List<String> schools = policy.acceptedSchoolList();
        if (!schools.isEmpty()) {
            if (ai.schoolName() == null) {
                review.add("Không đọc được tên trường");
            } else if (schools.stream().noneMatch(s -> normalize(ai.schoolName()).contains(normalize(s)))) {
                (confident ? reject : review).add("Trường \"" + ai.schoolName() + "\" không thuộc danh sách được ưu đãi");
            }
        }
        if (policy.isRequireNameMatch() && !sameName(declared.fullName(), ai.fullName())) {
            review.add("Họ tên khai báo \"" + declared.fullName() + "\" khác trên thẻ \"" + ai.fullName() + "\"");
        }
        if (policy.isRequireAccountNameMatch() && !sameName(declared.fullName(), declared.accountFullName())) {
            review.add("Họ tên khai báo khác họ tên tài khoản \"" + declared.accountFullName() + "\"");
        }
        if (policy.isRequireStudentCodeMatch() && !sameCode(declared.studentCode(), ai.studentCode())) {
            review.add("Mã sinh viên khai báo \"" + declared.studentCode() + "\" khác trên thẻ \"" + ai.studentCode() + "\"");
        }

        if (!reject.isEmpty()) {
            List<String> all = new ArrayList<>(reject);
            all.addAll(review);
            return new Decision(Outcome.REJECT, all, null);
        }
        if (!review.isEmpty()) {
            return new Decision(Outcome.REVIEW, review, null);
        }
        if (!policy.isAutoApprove()) {
            return new Decision(Outcome.REVIEW, List.of("Đạt mọi điều kiện, chờ duyệt tay (tắt tự động duyệt)"), null);
        }
        return new Decision(Outcome.APPROVE, List.of("Đạt mọi điều kiện"), approvedUntil(policy, ai.validUntil(), today));
    }

    /** Hạn ưu đãi = ngày hết hạn thẻ, nhưng không quá max_validity_months; thẻ không ghi hạn → default_validity_months. */
    public static LocalDate approvedUntil(StudentPolicy policy, LocalDate cardValidUntil, LocalDate today) {
        LocalDate cap = today.plusMonths(policy.getMaxValidityMonths());
        if (cardValidUntil == null) {
            return today.plusMonths(policy.getDefaultValidityMonths());
        }
        return cardValidUntil.isAfter(cap) ? cap : cardValidUntil;
    }

    static boolean sameName(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }

    static boolean sameCode(String a, String b) {
        return a != null && b != null
                && a.replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(b.replaceAll("[^A-Za-z0-9]", ""));
    }

    /** Bỏ dấu tiếng Việt, viết hoa, gộp khoảng trắng: "Nguyễn  Văn Ân" → "NGUYEN VAN AN". */
    static String normalize(String s) {
        String noAccent = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return noAccent.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String note(StudentCardExtraction ai) {
        return ai.notes() == null ? "" : " (" + ai.notes() + ")";
    }
}
