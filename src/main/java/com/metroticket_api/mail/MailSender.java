package com.metroticket_api.mail;

/**
 * Cổng gửi mail của hệ thống. Code nghiệp vụ chỉ phụ thuộc interface này,
 * nhờ vậy có thể thay SMTP bằng dịch vụ khác (SES, SendGrid...) hoặc mock khi test.
 */
public interface MailSender {

    void send(MailMessage message);
}
