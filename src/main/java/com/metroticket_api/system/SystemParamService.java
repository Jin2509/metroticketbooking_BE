package com.metroticket_api.system;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.security.CurrentUser;

/**
 * Tham số hệ thống có hiệu lực theo thời gian (UC09). Sửa tham số tạo phiên bản mới và đóng phiên bản trước tại mốc
 * áp dụng, nên giao dịch đã xảy ra không bị ảnh hưởng và luôn còn lịch sử.
 *
 * <p>Khoá cố định nằm trong {@link SystemParamKey}. Khoá động FARE_PER_KM_&lt;mã loại vé&gt; là đơn giá/km của từng
 * loại vé lượt, được tạo khi tạo loại vé lượt và chỉ sửa được khi đã tồn tại.</p>
 */
@Service
public class SystemParamService {

    /** Giới hạn giá trị của một khoá (cố định hoặc động). */
    private record Spec(String key, int min, int max) {
    }

    private final SystemParamRepository params;
    private final AuditLogService audit;
    private final Clock clock;

    public SystemParamService(SystemParamRepository params, AuditLogService audit, Clock clock) {
        this.params = params;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public int intValue(SystemParamKey key) {
        return params.findEffective(key.name(), clock.instant())
                .map(p -> Integer.parseInt(p.getParamValue()))
                .orElse(key.defaultValue());
    }

    /** Đơn giá/km đang hiệu lực của loại vé lượt; thiếu cấu hình thì dùng mặc định. */
    @Transactional(readOnly = true)
    public int farePerKm(String productCode) {
        return params.findEffective(SystemParamKey.farePerKmKey(productCode), clock.instant())
                .map(p -> Integer.parseInt(p.getParamValue()))
                .orElse(SystemParamKey.FARE_PER_KM_DEFAULT);
    }

    /** Tạo tham số đơn giá/km cho loại vé lượt mới (áp dụng ngay). Đã có thì giữ nguyên. */
    @Transactional
    public void createFarePerKm(String productCode, String productName, int value) {
        String key = SystemParamKey.farePerKmKey(productCode);
        checkRange(key, value, SystemParamKey.FARE_PER_KM_MIN, SystemParamKey.FARE_PER_KM_MAX);
        if (params.findLatestForUpdate(key).isPresent()) {
            return;
        }
        Instant now = clock.instant();
        SystemParam saved = params.save(new SystemParam(key, "fare", Integer.toString(value),
                "Đơn giá theo km của vé lượt " + productName + " (đồng/km)", now,
                CurrentUser.optionalId().orElse(null), now));
        audit.log("SYSTEM_PARAM_CREATE", "system_param", saved.getId(), key, null, saved.getParamValue());
    }

    @Transactional(readOnly = true)
    public List<SystemParam> latest() {
        return params.findByEffectiveToIsNullOrderByParamGroupAscParamKeyAsc();
    }

    @Transactional(readOnly = true)
    public List<SystemParam> history(String rawKey) {
        return params.findByParamKeyOrderByEffectiveFromDesc(parse(rawKey).key());
    }

    /** @param effectiveFrom null nghĩa là áp dụng ngay */
    @Transactional
    public SystemParam update(String rawKey, int value, Instant effectiveFrom) {
        Spec spec = parse(rawKey);
        checkRange(spec.key(), value, spec.min(), spec.max());
        Instant now = clock.instant();
        Instant from = effectiveFrom == null ? now : effectiveFrom;
        if (from.isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời gian áp dụng không được ở quá khứ");
        }
        SystemParam latest = params.findLatestForUpdate(spec.key())
                .orElseThrow(() -> spec.key().startsWith(SystemParamKey.FARE_PER_KM_PREFIX)
                        ? new ResponseStatusException(HttpStatus.NOT_FOUND, "Không có tham số " + spec.key())
                        : new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Thiếu tham số " + spec.key()));
        if (!from.isAfter(latest.getEffectiveFrom())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tham số đã có giá trị áp dụng từ " + latest.getEffectiveFrom() + ", hãy chọn thời gian sau mốc này");
        }
        latest.close(from);
        params.flush(); // đóng dòng cũ trước khi thêm dòng mới vì mỗi tham số chỉ có một dòng đang mở
        SystemParam next = params.save(new SystemParam(latest, Integer.toString(value), from,
                CurrentUser.optionalId().orElse(null), now));
        audit.log("SYSTEM_PARAM_UPDATE", "system_param", next.getId(), spec.key() + " áp dụng từ " + from,
                latest.getParamValue(), next.getParamValue());
        return next;
    }

    private static void checkRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giá trị của " + key + " phải từ " + min + " đến " + max);
        }
    }

    private static Spec parse(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim().toUpperCase(Locale.ROOT);
        if (key.startsWith(SystemParamKey.FARE_PER_KM_PREFIX) && key.length() > SystemParamKey.FARE_PER_KM_PREFIX.length()) {
            return new Spec(key, SystemParamKey.FARE_PER_KM_MIN, SystemParamKey.FARE_PER_KM_MAX);
        }
        try {
            SystemParamKey fixed = SystemParamKey.valueOf(key);
            return new Spec(fixed.name(), fixed.min(), fixed.max());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không có tham số " + rawKey);
        }
    }
}
