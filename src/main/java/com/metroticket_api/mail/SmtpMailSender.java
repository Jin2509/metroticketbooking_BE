package com.metroticket_api.mail;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Gửi mail qua SMTP bằng JavaMailSender do Spring Boot tự cấu hình
 * từ các khoá spring.mail.*.
 */
@Component
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private final JavaMailSender javaMailSender;
    private final MailProps props;

    public SmtpMailSender(JavaMailSender javaMailSender, MailProps props) {
        this.javaMailSender = javaMailSender;
        this.props = props;
    }

    @Override
    public void send(MailMessage message) {
        try {
            MimeMessage mime = javaMailSender.createMimeMessage();
            // multipart = true khi có cả text và HTML
            MimeMessageHelper helper = new MimeMessageHelper(mime, message.hasHtml(), StandardCharsets.UTF_8.name());
            helper.setFrom(props.from(), props.fromName());
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            if (message.hasHtml()) {
                helper.setText(message.textBody(), message.htmlBody());
            } else {
                helper.setText(message.textBody(), false);
            }
            javaMailSender.send(mime);
            log.info("Đã gửi mail '{}' tới {}", message.subject(), mask(message.to()));
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            throw new MailSendException("Không gửi được mail tới " + mask(message.to()), e);
        }
    }

    /** Che bớt địa chỉ email trong log: an***@gmail.com */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 2) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
