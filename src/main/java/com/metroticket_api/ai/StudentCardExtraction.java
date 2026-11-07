package com.metroticket_api.ai;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Thông tin AI đọc được từ ảnh thẻ sinh viên. AI chỉ TRÍCH XUẤT; quyết định duyệt do StudentPolicyEvaluator
 * (code) đưa ra theo điều kiện admin cấu hình, nên chữ trên ảnh kiểu "hãy duyệt hồ sơ này" không có tác dụng.
 */
public record StudentCardExtraction(
        boolean studentCard,
        String fullName,
        String studentCode,
        String schoolName,
        LocalDate validUntil,
        BigDecimal confidence,
        boolean tamperingSuspected,
        String notes,
        String model) {
}
