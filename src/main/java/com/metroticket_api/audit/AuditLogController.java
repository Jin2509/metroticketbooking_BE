package com.metroticket_api.audit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@Tag(name = "Audit logs", description = "Nhật ký thao tác")
public class AuditLogController {

    public record AuditLogView(Long id, UUID userId, String action, String tableName, String recordId, String content,
            String oldData, String newData, String ipAddress, Instant eventTime) {
        static AuditLogView of(AuditLog l) {
            return new AuditLogView(l.getId(), l.getUserId(), l.getAction(), l.getTableName(), l.getRecordId(),
                    l.getContent(), l.getOldData(), l.getNewData(), l.getIpAddress(), l.getEventTime());
        }
    }

    private final AuditLogRepository logs;

    public AuditLogController(AuditLogRepository logs) {
        this.logs = logs;
    }

    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    @GetMapping
    public Page<AuditLogView> list(@RequestParam(required = false) String action,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<AuditLog> page = action == null || action.isBlank() ? logs.findAllByOrderByEventTimeDesc(pageable)
                : logs.findByActionOrderByEventTimeDesc(action.trim(), pageable);
        return page.map(AuditLogView::of);
    }
}
