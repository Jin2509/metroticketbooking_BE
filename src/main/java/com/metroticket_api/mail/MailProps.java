package com.metroticket_api.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Ánh xạ các khoá app.mail.* trong application.properties.
 * Nếu thiếu SMTP_FROM_ADDRESS, ứng dụng sẽ báo lỗi ngay khi khởi động.
 */
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record MailProps(
        @NotBlank @Email String from,
        @NotBlank String fromName) {
}
