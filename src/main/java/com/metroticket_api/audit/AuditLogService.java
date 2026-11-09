package com.metroticket_api.audit;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.metroticket_api.security.CurrentUser;

/** Ghi nhật ký trong transaction của nghiệp vụ: nghiệp vụ rollback thì nhật ký cũng không còn. */
@Service
public class AuditLogService {

    private final AuditLogRepository logs;
    private final Clock clock;

    public AuditLogService(AuditLogRepository logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    @Transactional
    public void log(String action, String tableName, Object recordId, String content, String oldData, String newData) {
        logs.save(new AuditLog(CurrentUser.optionalId().orElse(null), action, tableName,
                recordId == null ? null : recordId.toString(), content, oldData, newData, clientIp(), clock.instant()));
    }

    @Transactional
    public void log(String action, String tableName, Object recordId, String content) {
        log(action, tableName, recordId, content, null, null);
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String ip = attrs.getRequest().getRemoteAddr();
            return ip != null && ip.length() <= 45 ? ip : null;
        }
        return null;
    }
}
