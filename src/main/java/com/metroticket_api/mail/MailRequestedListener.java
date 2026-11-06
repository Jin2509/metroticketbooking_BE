package com.metroticket_api.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.metroticket_api.config.AsyncConfig;

/**
 * AFTER_COMMIT: không gửi mail cho thao tác bị rollback.
 * @Async: SMTP chậm hoặc lỗi không làm hỏng thao tác đã hoàn tất.
 */
@Component
public class MailRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(MailRequestedListener.class);

    private final MailSender mailSender;

    public MailRequestedListener(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMailRequested(MailRequested event) {
        try {
            mailSender.send(event.message());
        } catch (MailSendException e) {
            log.error("Gửi mail '{}' thất bại: {}", event.message().subject(), e.getMessage(), e);
        }
    }
}
