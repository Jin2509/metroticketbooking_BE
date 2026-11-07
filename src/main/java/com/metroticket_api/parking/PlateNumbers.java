package com.metroticket_api.parking;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chuẩn hoá biển số Việt Nam: bỏ khoảng trắng, '-', '.', viết hoa. Ví dụ "59-X1 123.45" → "59X112345",
 * "51A-123.45" → "51A12345". Định dạng: 2 số tỉnh + seri (1 chữ, có thể thêm 1 chữ/số) + 4–6 số.
 */
public final class PlateNumbers {

    private static final Pattern VALID = Pattern.compile("^[0-9]{2}[A-Z][A-Z0-9]?[0-9]{4,6}$");

    private PlateNumbers() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\s.\\-]", "").toUpperCase(Locale.ROOT);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Chuẩn hoá và kiểm tra; trả null nếu xe không cần biển số và client không gửi. */
    public static String require(String raw, VehicleType vehicleType) {
        String plate = normalize(raw);
        if (plate == null) {
            if (vehicleType.requiresPlate()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Biển số xe là bắt buộc với " + vehicleType);
            }
            return null;
        }
        if (!VALID.matcher(plate).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Biển số xe không hợp lệ: " + raw);
        }
        return plate;
    }
}
