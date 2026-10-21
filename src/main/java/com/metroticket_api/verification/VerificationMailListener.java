package com.metroticket_api.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.metroticket_api.config.AsyncConfig;
import com.metroticket_api.mail.MailSendException;
import com.metroticket_api.mail.MailSender;
import com.metroticket_api.mail.MailTemplates;

/**
 * Nghe sự kiện VerificationCodeIssued và gửi mail.
 *
 * AFTER_COMMIT: chỉ gửi khi mã đã lưu thành công vào DB (nếu transaction rollback thì không gửi).
 * @Async: chạy trên mailExecutor, request HTTP trả về ngay không chờ SMTP.
 */
@Component
public class VerificationMailListener {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailListener.class);

    private final MailSender mailSender;
    private final MailTemplates templates;

    public VerificationMailListener(MailSender mailSender, MailTemplates templates) {
        this.mailSender = mailSender;
        this.templates = templates;
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCodeIssued(VerificationCodeIssued event) {
        try {
            mailSender.send(templates.verificationCode(event.email(), event.code(), event.lifetime()));
        } catch (MailSendException e) {
            // Người dùng có thể bấm "gửi lại mã" sau cooldown
            log.error("Gửi mã xác thực thất bại: {}", e.getMessage(), e);
        }
    }
}
