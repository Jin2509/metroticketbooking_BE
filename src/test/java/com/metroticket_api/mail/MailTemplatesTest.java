package com.metroticket_api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.metroticket_api.verification.VerificationPurpose;

class MailTemplatesTest {

    private static final String TO = "an@example.com";

    private final MailTemplates templates = new MailTemplates(engine(),
            Clock.fixed(Instant.parse("2026-11-03T07:30:00Z"), ZoneOffset.UTC));

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    @ParameterizedTest
    @EnumSource(VerificationPurpose.class)
    void verificationCodeUsesSharedLayout(VerificationPurpose purpose) {
        MailMessage mail = templates.verificationCode(TO, purpose, "012345", Duration.ofMinutes(10));

        assertThat(mail.subject()).startsWith("[MetroTicket] ");
        assertThat(mail.htmlBody())
                .contains("012345", "MetroTicket", "14:30 03/11/2026 (GMT+7)", TO)
                .doesNotContain("th:text", "th:replace", "xmlns:th");
        assertThat(mail.textBody()).contains("012345", "10 phút");
    }

    /** Dòng xem trước hiện trên màn hình khoá nên không được chứa mã. */
    @Test
    void codeIsNotInPreheader() {
        String html = templates.verificationCode(TO, VerificationPurpose.LOGIN_2FA, "012345", Duration.ofMinutes(10)).htmlBody();

        assertThat(html.indexOf("012345")).isGreaterThan(html.indexOf("<h1"));
    }

    /** Họ tên do người dùng tự nhập, không được chèn được HTML vào mail. */
    @Test
    void accountNoticeEscapesUserInput() {
        MailMessage mail = templates.accountNotice(TO, "<img src=x onerror=alert(1)>", "Tiêu đề", "Nội dung");

        assertThat(mail.htmlBody()).doesNotContain("<img").contains("&lt;img");
        assertThat(mail.textBody()).startsWith("Xin chào <img src=x onerror=alert(1)>,");
    }

    @Test
    void credentialsAreInBodyButNotPreheader() {
        MailMessage mail = templates.adminCreatedAccountNotice(TO, "Tam@12345");

        assertThat(mail.htmlBody().indexOf("Tam@12345")).isGreaterThan(mail.htmlBody().indexOf("<h1"));
        assertThat(mail.textBody()).contains("Email đăng nhập: " + TO, "Mật khẩu tạm thời: Tam@12345");
    }

    @Test
    void noticesWithoutDetailsRenderCleanly() {
        MailMessage mail = templates.accountNotice(TO, "An", "Tiêu đề", "Nội dung");

        assertThat(mail.htmlBody()).contains("Xin chào An,", "Nội dung").doesNotContain("Cảnh báo", "Nhãn");
    }

    @Test
    void emailChangedNoticeGoesToOldAddressAndNamesNewOne() {
        MailMessage mail = templates.emailChangedNotice(TO, "moi@example.com");

        assertThat(mail.to()).isEqualTo(TO);
        assertThat(mail.htmlBody()).contains("moi@example.com", "liên hệ quản trị viên");
    }
}
