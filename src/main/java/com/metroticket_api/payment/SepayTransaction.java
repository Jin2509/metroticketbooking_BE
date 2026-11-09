package com.metroticket_api.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Bản ghi gốc của mỗi giao dịch SePay báo về. Khoá chính = id của SePay → xử lý đúng 1 lần dù SePay gửi lại. */
@Entity
@Table(name = "sepay_transaction")
public class SepayTransaction {

    @Id
    private Long id;

    @Column(length = 100)
    private String gateway;

    @Column(name = "transaction_date", length = 30)
    private String transactionDate;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "sub_account", length = 50)
    private String subAccount;

    @Column(length = 100)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "transfer_type", nullable = false, length = 10)
    private String transferType;

    @Column(name = "transfer_amount", nullable = false)
    private long transferAmount;

    @Column(name = "reference_code", length = 100)
    private String referenceCode;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SepayResult result;

    @Column(length = 255)
    private String note;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    private boolean reconciled;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "reconciled_by")
    private UUID reconciledBy;

    @Column(name = "reconcile_note", columnDefinition = "TEXT")
    private String reconcileNote;

    protected SepayTransaction() {
    }

    public SepayTransaction(SepayWebhookPayload p, Instant receivedAt) {
        this.id = p.id();
        this.gateway = truncate(p.gateway(), 100);
        this.transactionDate = truncate(p.transactionDate(), 30);
        this.accountNumber = truncate(p.accountNumber(), 50);
        this.subAccount = truncate(p.subAccount(), 50);
        this.code = truncate(p.code(), 100);
        this.content = p.content();
        this.transferType = truncate(p.transferType(), 10);
        this.transferAmount = p.transferAmount();
        this.referenceCode = truncate(p.referenceCode(), 100);
        this.description = p.description();
        this.receivedAt = receivedAt;
        this.result = SepayResult.UNMATCHED;
    }

    public void resolve(SepayResult result, UUID orderId, String note) {
        this.result = result;
        this.orderId = orderId;
        this.note = truncate(note, 255);
    }

    void markReconciled(UUID by, Instant at) {
        reconciled = true;
        reconciledBy = by;
        reconciledAt = at;
    }

    void addReconcileNote(String note) {
        reconcileNote = note;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() { return id; }
    public String getGateway() { return gateway; }
    public String getTransactionDate() { return transactionDate; }
    public String getAccountNumber() { return accountNumber; }
    public String getCode() { return code; }
    public String getContent() { return content; }
    public String getTransferType() { return transferType; }
    public long getTransferAmount() { return transferAmount; }
    public String getReferenceCode() { return referenceCode; }
    public UUID getOrderId() { return orderId; }
    public SepayResult getResult() { return result; }
    public String getNote() { return note; }
    public Instant getReceivedAt() { return receivedAt; }
    public boolean isReconciled() { return reconciled; }
    public Instant getReconciledAt() { return reconciledAt; }
    public String getReconcileNote() { return reconcileNote; }
}
