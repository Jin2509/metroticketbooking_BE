package com.metroticket_api.mail;

import java.util.Objects;

/**
 * Một email cần gửi. Luôn có bản text (cho client không hiển thị HTML)
 * và có thể có thêm bản HTML.
 */
public record MailMessage(String to, String subject, String textBody, String htmlBody) {

    public MailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(textBody, "textBody");
    }

    public static MailMessage text(String to, String subject, String textBody) {
        return new MailMessage(to, subject, textBody, null);
    }

    public boolean hasHtml() {
        return htmlBody != null && !htmlBody.isBlank();
    }
}
