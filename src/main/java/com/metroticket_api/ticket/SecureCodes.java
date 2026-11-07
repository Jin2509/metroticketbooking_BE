package com.metroticket_api.ticket;

import java.security.SecureRandom;
import java.util.Base64;

/** Sinh mã vé / mã QR khó đoán. Dùng chung cho vé Metro và gửi xe. */
public final class SecureCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Bỏ các ký tự dễ nhầm (0/O, 1/I/L) để nhân viên đọc mã bằng mắt. */
    private static final char[] HUMAN_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private SecureCodes() {
    }

    /** Token 256-bit, base64url (43 ký tự) để mã hoá vào QR. */
    public static String qrToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Mã ngắn dễ đọc, ví dụ prefix "MT" → "MT7KQ2X9HD4P". */
    public static String humanCode(String prefix, int length) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(HUMAN_ALPHABET[RANDOM.nextInt(HUMAN_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
