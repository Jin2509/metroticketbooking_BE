package com.metroticket_api.mail;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.metroticket_api.verification.VerificationPurpose;

@Component 
public class MailTemplates {
    private static final Locale VI = Locale.forLanguageTag("vi-VN");
    private final TemplateEngine templateEngine;

    public MailTemplates(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public MailMessage verificationCode(String to, VerificationPurpose purpose, String code, Duration lifetime) {
        String subject = switch (purpose) {
            case REGISTER -> "[MetroTicket] Mã xác thực email";
            case RESET_PASSWORD -> "[MetroTicket] Mã xác thực đặt lại mật khẩu";
            case CHANGE_EMAIL -> "[MetroTicket] Xác thực thay đổi email";
        };
        String intro = switch (purpose) {
            case REGISTER -> "Cảm ơn bạn đã đăng ký MetroTicket. Mã xác thực email của bạn là:";
            case RESET_PASSWORD -> "Bạn vừa yêu cầu đặt lại mật khẩu MetroTicket. Mã xác thực của bạn là:";
            case CHANGE_EMAIL -> "Bạn vừa yêu cầu thay đổi email đăng nhập MetroTicket. Mã xác thực mới của bạn là:";
        };

        Context ctx = new Context(VI);
        ctx.setVariable("intro", intro);
        ctx.setVariable("code", code);
        ctx.setVariable("minutes", lifetime.toMinutes());

        String html = templateEngine.process("mail/verification-code", ctx);
        String text = """
            %s %s
            Mã có hiệu lực trong %d phút. Không chia sẻ mã này cho bất kỳ ai.
            """.formatted(intro, code, lifetime.toMinutes());
        return new MailMessage(to, subject, text, html);
    }

    public MailMessage adminPasswordResetNotice(String to, String newPassword) {
        String subject = "[MetroTicket] Mật khẩu tài khoản đã được thiết lập lại";
        String text = """
            Xin chào,
            Mật khẩu mới của bạn đã được quản trị viên đặt lại.
            Mật khẩu mới: %s
            Vui lòng đăng nhập và đổi mật khẩu ngay sau khi đăng nhập lần đầu.
            """.formatted(newPassword);
        return MailMessage.text(to, subject, text);
    }

    public MailMessage adminCreatedAccountNotice(String to, String temporaryPassword) {
        String subject = "[MetroTicket] Tài khoản của bạn đã được tạo";
        String text = """
            Xin chào,
            Tài khoản MetroTicket của bạn đã được tạo bởi quản trị viên.
            Email đăng nhập: %s
            Mật khẩu tạm thời: %s
            Vui lòng đăng nhập và đổi mật khẩu ngay sau khi đăng nhập lần đầu.
            """.formatted(to, temporaryPassword);
        return MailMessage.text(to, subject, text);
    }
}
