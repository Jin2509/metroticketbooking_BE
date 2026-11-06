package com.metroticket_api.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.metroticket_api.verification.VerificationPurpose;

/**
 * Dựng nội dung mail. Mọi mail đều có bản HTML (khung chung ở templates/mail/layout.html)
 * và bản text. Dữ liệu động chỉ được đưa vào HTML qua th:text để luôn được escape.
 */
@Component
public class MailTemplates {

    private static final Locale VI = Locale.forLanguageTag("vi-VN");
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy '(GMT+7)'");
    private static final String SUBJECT_PREFIX = "[MetroTicket] ";
    private static final String CATEGORY_VERIFICATION = "Xác thực tài khoản";
    private static final String CATEGORY_SECURITY = "Bảo mật tài khoản";
    private static final String CATEGORY_ACCOUNT = "Thông báo tài khoản";
    private static final String DEFAULT_GREETING = "Xin chào,";

    private final TemplateEngine templateEngine;
    private final Clock clock;

    public MailTemplates(TemplateEngine templateEngine, Clock clock) {
        this.templateEngine = templateEngine;
        this.clock = clock;
    }

    public MailMessage verificationCode(String to, VerificationPurpose purpose, String code, Duration lifetime) {
        CodeCopy copy = switch (purpose) {
            case REGISTER -> new CodeCopy("Mã xác thực email", "Xác thực địa chỉ email",
                    "Cảm ơn bạn đã đăng ký MetroTicket. Nhập mã dưới đây để kích hoạt tài khoản.",
                    "Chỉ nhập mã nếu chính bạn vừa đăng ký. Nếu không phải bạn, hãy bỏ qua email này.");
            case RESET_PASSWORD -> new CodeCopy("Mã xác thực đặt lại mật khẩu", "Đặt lại mật khẩu",
                    "Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn. Nhập mã dưới đây để tiếp tục.",
                    "Nếu không phải bạn, hãy bỏ qua email này, mật khẩu hiện tại vẫn được giữ nguyên.");
            case CHANGE_EMAIL -> new CodeCopy("Xác thực thay đổi email", "Xác nhận email đăng nhập mới",
                    "Nhập mã dưới đây để xác nhận đây là email đăng nhập mới của tài khoản MetroTicket.",
                    "Nếu không phải bạn, hãy bỏ qua email này, email đăng nhập sẽ không thay đổi.");
            case LOGIN_2FA -> new CodeCopy("Mã xác nhận đăng nhập", "Xác nhận đăng nhập",
                    "Có người vừa nhập đúng mật khẩu tài khoản của bạn. Nếu là bạn, hãy nhập mã dưới đây để hoàn tất đăng nhập.",
                    "Nếu không phải bạn, hãy đổi mật khẩu ngay vì mật khẩu của bạn có thể đã bị lộ.");
        };

        Context ctx = baseContext(to, copy.heading(), copy.intro(),
                purpose == VerificationPurpose.REGISTER ? CATEGORY_VERIFICATION : CATEGORY_SECURITY);
        ctx.setVariable("intro", copy.intro());
        ctx.setVariable("code", code);
        ctx.setVariable("minutes", lifetime.toMinutes());
        ctx.setVariable("warning", copy.warning());

        String html = templateEngine.process("mail/verification-code", ctx);
        String text = """
            %s

            Mã xác thực: %s
            Mã có hiệu lực trong %d phút.

            Không chia sẻ mã này cho bất kỳ ai. %s
            %s""".formatted(copy.intro(), code, lifetime.toMinutes(), copy.warning(), textFooter(ctx));
        return new MailMessage(to, SUBJECT_PREFIX + copy.subject(), text, html);
    }

    /** Bản email của một thông báo tài khoản (xem NotificationService.notifyWithEmail). */
    public MailMessage accountNotice(String to, String fullName, String title, String body) {
        return notice(to, CATEGORY_ACCOUNT, title, title, "Xin chào " + fullName + ",", body, List.of(), null);
    }

    /** Gửi tới email CŨ, để chủ tài khoản biết nếu việc đổi email không phải do họ thực hiện. */
    public MailMessage emailChangedNotice(String oldEmail, String newEmail) {
        return notice(oldEmail, CATEGORY_SECURITY, "Email đăng nhập đã được thay đổi", "Email đăng nhập đã thay đổi",
                DEFAULT_GREETING, "Email đăng nhập tài khoản MetroTicket của bạn vừa được thay đổi.",
                List.of(new Detail("Email đăng nhập mới", newEmail)),
                "Nếu bạn không thực hiện thay đổi này, hãy liên hệ quản trị viên ngay.");
    }

    public MailMessage adminPasswordResetNotice(String to, String newPassword) {
        return notice(to, CATEGORY_SECURITY, "Mật khẩu tài khoản đã được thiết lập lại", "Mật khẩu đã được đặt lại",
                DEFAULT_GREETING, "Quản trị viên vừa đặt lại mật khẩu tài khoản MetroTicket của bạn.",
                List.of(new Detail("Mật khẩu mới", newPassword)),
                "Vui lòng đăng nhập và đổi mật khẩu ngay.");
    }

    public MailMessage adminCreatedAccountNotice(String to, String temporaryPassword) {
        return notice(to, CATEGORY_ACCOUNT, "Tài khoản của bạn đã được tạo", "Tài khoản đã được tạo",
                DEFAULT_GREETING, "Quản trị viên vừa tạo tài khoản MetroTicket cho bạn.",
                List.of(new Detail("Email đăng nhập", to), new Detail("Mật khẩu tạm thời", temporaryPassword)),
                "Vui lòng đăng nhập và đổi mật khẩu ngay sau lần đăng nhập đầu tiên.");
    }

    private MailMessage notice(String to, String category, String subject, String title, String greeting, String body,
            List<Detail> details, String warning) {
        // Không đưa dữ liệu nhạy cảm trong details vào dòng xem trước: nó hiện ngay trên màn hình khoá
        Context ctx = baseContext(to, title, body, category);
        ctx.setVariable("greeting", greeting);
        ctx.setVariable("body", body);
        ctx.setVariable("details", details);
        ctx.setVariable("warning", warning);

        String html = templateEngine.process("mail/notice", ctx);
        String detailLines = details.stream()
                .map(d -> d.label() + ": " + d.value() + "\n")
                .collect(Collectors.joining());
        String text = "%s\n\n%s\n\n%s%s%s".formatted(greeting, body,
                detailLines.isEmpty() ? "" : detailLines + "\n",
                warning == null ? "" : warning + "\n",
                textFooter(ctx));
        return new MailMessage(to, SUBJECT_PREFIX + subject, text, html);
    }

    private Context baseContext(String to, String title, String preheader, String category) {
        ZonedDateTime now = clock.instant().atZone(VIETNAM);
        Context ctx = new Context(VI);
        ctx.setVariable("title", title);
        ctx.setVariable("preheader", preheader);
        ctx.setVariable("category", category);
        ctx.setVariable("recipient", to);
        ctx.setVariable("sentAt", SENT_AT.format(now));
        ctx.setVariable("year", now.getYear());
        return ctx;
    }

    private static String textFooter(Context ctx) {
        return """

            Thời gian gửi: %s
            --
            Email tự động từ MetroTicket, vui lòng không trả lời.
            MetroTicket không bao giờ yêu cầu bạn cung cấp mật khẩu hoặc mã xác thực qua email hay điện thoại.
            """.formatted(ctx.getVariable("sentAt"));
    }

    private record CodeCopy(String subject, String heading, String intro, String warning) {
    }

    /** Một dòng trong bảng thông tin của mail. Phải public để biểu thức trong template đọc được. */
    public record Detail(String label, String value) {
    }
}
