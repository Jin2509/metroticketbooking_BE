package com.metroticket_api.notification.push;

import java.util.Map;

public interface PushSender {

    boolean available();

    Result send(String deviceToken, Message message);

    record Message(String title, String body, boolean highPriority, Map<String, String> data) {
    }

    enum Outcome {
        SENT,
        /** Token hết hiệu lực (gỡ app, đổi máy) → vô hiệu thiết bị. */
        INVALID_TOKEN,
        /** Lỗi tạm thời (429, 5xx, mạng) → gửi lại sau. */
        RETRY,
        FAILED
    }

    record Result(Outcome outcome, String error) {
        public static Result sent() {
            return new Result(Outcome.SENT, null);
        }
    }
}
