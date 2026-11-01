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
            case LOGIN_2FA -> "[MetroTicket] Mã xác nhận đăng nhập";
        };
        String intro = switch (purpose) {
            case REGISTER -> "Cảm ơn bạn đã đăng ký MetroTicket. Mã xác thực email của bạn là:";
            case RESET_PASSWORD -> "Bạn vừa yêu cầu đặt lại mật khẩu MetroTicket. Mã xác thực của bạn là:";
            case CHANGE_EMAIL -> "Bạn vừa yêu cầu thay đổi email đăng nhập MetroTicket. Mã xác thực mới của bạn là:";
            case LOGIN_2FA -> "Có người vừa đăng nhập tài khoản MetroTicket của bạn bằng đúng mật khẩu. Nếu là bạn, hãy nhập mã xác nhận sau. Nếu không phải bạn, hãy đổi mật khẩu ngay. Mã xác nhận là:";
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

    /** Bản email của một thông báo tài khoản (xem NotificationService.notifyWithEmail). */
    public MailMessage accountNotice(String to, String fullName, String title, String body) {
        String text = """
            Xin chào %s,
            %s
            """.formatted(fullName, body);
        return MailMessage.text(to, "[MetroTicket] " + title, text);
    }

    /** Gửi tới email CŨ, để chủ tài khoản biết nếu việc đổi email không phải do họ thực hiện. */
    public MailMessage emailChangedNotice(String oldEmail, String newEmail) {
        String subject = "[MetroTicket] Email đăng nhập đã được thay đổi";
        String text = """
            Xin chào,
            Email đăng nhập tài khoản MetroTicket của bạn vừa được đổi thành: %s
            Nếu bạn không thực hiện thay đổi này, hãy liên hệ quản trị viên ngay.
            """.formatted(newEmail);
        return MailMessage.text(oldEmail, subject, text);
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
