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
 */
@Service
public class SystemParamService {

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

    @Transactional(readOnly = true)
    public List<SystemParam> latest() {
        return params.findByEffectiveToIsNullOrderByParamGroupAscParamKeyAsc();
    }

    @Transactional(readOnly = true)
    public List<SystemParam> history(String rawKey) {
        return params.findByParamKeyOrderByEffectiveFromDesc(parse(rawKey).name());
    }

    /** @param effectiveFrom null nghĩa là áp dụng ngay */
    @Transactional
    public SystemParam update(String rawKey, int value, Instant effectiveFrom) {
        SystemParamKey key = parse(rawKey);
        if (value < key.min() || value > key.max()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Giá trị của " + key + " phải từ " + key.min() + " đến " + key.max());
        }
        Instant now = clock.instant();
        Instant from = effectiveFrom == null ? now : effectiveFrom;
        if (from.isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời gian áp dụng không được ở quá khứ");
        }
        SystemParam latest = params.findLatestForUpdate(key.name())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Thiếu tham số " + key));
        if (!from.isAfter(latest.getEffectiveFrom())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tham số đã có giá trị áp dụng từ " + latest.getEffectiveFrom() + ", hãy chọn thời gian sau mốc này");
        }
        latest.close(from);
        params.flush(); // đóng dòng cũ trước khi thêm dòng mới vì mỗi tham số chỉ có một dòng đang mở
        SystemParam next = params.save(new SystemParam(latest, Integer.toString(value), from,
                CurrentUser.optionalId().orElse(null), now));
        audit.log("SYSTEM_PARAM_UPDATE", "system_param", next.getId(), key + " áp dụng từ " + from,
                latest.getParamValue(), next.getParamValue());
        return next;
    }

    private static SystemParamKey parse(String rawKey) {
        try {
            return SystemParamKey.valueOf(rawKey.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không có tham số " + rawKey);
        }
    }
}
