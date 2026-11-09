package com.metroticket_api.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Một dòng nhật ký thao tác: ai làm gì, trên bản ghi nào, giá trị cũ và mới. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "table_name", length = 100)
    private String tableName;

    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "old_data", columnDefinition = "TEXT")
    private String oldData;

    @Column(name = "new_data", columnDefinition = "TEXT")
    private String newData;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    protected AuditLog() {
    }

    AuditLog(UUID userId, String action, String tableName, String recordId, String content, String oldData,
            String newData, String ipAddress, Instant eventTime) {
        this.userId = userId;
        this.action = action;
        this.tableName = tableName;
        this.recordId = recordId;
        this.content = content;
        this.oldData = oldData;
        this.newData = newData;
        this.ipAddress = ipAddress;
        this.eventTime = eventTime;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getAction() { return action; }
    public String getTableName() { return tableName; }
    public String getRecordId() { return recordId; }
    public String getContent() { return content; }
    public String getOldData() { return oldData; }
    public String getNewData() { return newData; }
    public String getIpAddress() { return ipAddress; }
    public Instant getEventTime() { return eventTime; }
}
