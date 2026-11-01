package com.metroticket_api.mail;

/** Yêu cầu gửi một mail sau khi transaction commit (xem MailRequestedListener). */
public record MailRequested(MailMessage message) {

    @Override
    public String toString() {
        // nội dung có thể chứa mật khẩu tạm, tránh lộ nếu ai đó log cả object
        return "MailRequested[subject=" + message.subject() + "]";
    }
}
