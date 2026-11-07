package com.metroticket_api.student;

import java.util.UUID;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ảnh thẻ (dữ liệu cá nhân): bảng riêng, chỉ nạp khi cần, xoá sau thời gian lưu giữ. */
@Entity
@Table(name = "student_verification_image")
public class StudentVerificationImage {

    @Id
    @Column(name = "verification_id")
    private UUID verificationId;

    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    protected StudentVerificationImage() {
    }

    public StudentVerificationImage(UUID verificationId, byte[] content) {
        this.verificationId = verificationId;
        this.content = content;
    }

    public UUID getVerificationId() { return verificationId; }
    public byte[] getContent() { return content; }
}
