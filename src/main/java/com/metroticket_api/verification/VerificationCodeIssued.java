package com.metroticket_api.verification;

import java.time.Duration;

/**
 * Sự kiện phát ra khi một mã mới được cấp. Mã gốc chỉ tồn tại trong bộ nhớ
 * (trong sự kiện này) đủ lâu để gửi mail, không bao giờ ghi xuống DB hay log.
 */
public record VerificationCodeIssued(String email, VerificationPurpose purpose, String code, Duration lifetime) {

    @Override
    public String toString() {
        // tránh lộ mã nếu ai đó log cả object
        return "VerificationCodeIssued[email=" + email + ", purpose=" + purpose + "]";
    }
}
