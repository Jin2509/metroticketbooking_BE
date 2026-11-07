package com.metroticket_api.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metroticket_api.mail.MailSendException;
import com.metroticket_api.mail.MailSender;
import com.metroticket_api.notification.NotificationDelivery.Channel;
import com.metroticket_api.notification.push.PushSender;

/** Gửi push/email trong outbox theo lô. Gọi FCM/SMTP ngoài transaction để không giữ khoá DB. */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    static final int BATCH = 50;

    private final DeliveryProcessor processor;
    private final PushSender push;
    private final MailSender mail;

    public DeliveryWorker(DeliveryProcessor processor, PushSender push, MailSender mail) {
        this.processor = processor;
        this.push = push;
        this.mail = mail;
    }

    @Scheduled(fixedDelayString = "${app.notification.delivery-interval:5s}",
            initialDelayString = "${app.notification.delivery-interval:5s}")
    public void poll() {
        for (Long id : processor.claim(BATCH)) {
            process(id);
        }
    }

    void process(Long id) {
        try {
            DeliveryProcessor.Job job = processor.load(id);
            if (job == null) {
                return;
            }
            if (job.channel() == Channel.PUSH) {
                PushSender.Result result = push.send(job.deviceToken(), job.push());
                if (result.outcome() == PushSender.Outcome.SENT) {
                    processor.sent(id);
                } else {
                    processor.failed(id, result.outcome(), result.error());
                }
            } else {
                mail.send(job.mail());
                processor.sent(id);
            }
        } catch (MailSendException e) {
            processor.failed(id, PushSender.Outcome.RETRY, "SMTP: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Lỗi gửi thông báo {}", id, e);
            processor.failed(id, PushSender.Outcome.RETRY, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
