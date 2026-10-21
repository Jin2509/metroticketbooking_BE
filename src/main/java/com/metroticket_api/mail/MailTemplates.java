package com.metroticket_api.mail;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component 
public class MailTemplates {
    private static final Locale VI = Locale.forLanguageTag("vi-VN");
    private final TemplateEngine templateEngine;

    public MailTemplates(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public MailMessage verificationCode(String to, String code, Duration lifetime) {
        Context ctx = new Context(VI);
        ctx.setVariable("code", code);
        ctx.setVariable("minutes", lifetime.toMinutes());
        
        String html = templateEngine.process("mail/verification-code", ctx);
        String text = """
            Mã xác thực MetroTicket của bạn là: %s
            Mã có hiệu lực trong %d phút. Không chia sẻ mã này cho bất kỳ ai.
            """.formatted(code, lifetime.toMinutes());
        return new MailMessage(to, "[MetroTicket] Mã xác thực email", text, html);
    }
}
