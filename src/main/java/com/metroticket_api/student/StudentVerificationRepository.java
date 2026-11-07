package com.metroticket_api.student;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentVerificationRepository extends JpaRepository<StudentVerification, UUID> {

    /**
     * Bước 1 nhận việc cho worker: khoá các hồ sơ đến hạn, bỏ qua hồ sơ instance khác đang giữ (SKIP LOCKED)
     * → chạy được nhiều instance song song mà không xử lý trùng. Phải gọi trong transaction cùng {@link #markProcessing}.
     */
    @Query(value = """
            SELECT id FROM student_verification
            WHERE status = 'PENDING' AND ai_next_attempt_at <= :now
            ORDER BY ai_next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<UUID> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    /** Bước 2: đánh dấu PROCESSING và tăng số lần thử. */
    @Modifying(clearAutomatically = true)
    @Query("update StudentVerification v set v.status = com.metroticket_api.student.StudentVerificationStatus.PROCESSING, "
            + "v.aiLockedAt = :now, v.aiAttempts = v.aiAttempts + 1, v.version = v.version + 1 where v.id in :ids")
    int markProcessing(@Param("ids") List<UUID> ids, @Param("now") Instant now);

    /** Worker chết giữa chừng (restart, crash): trả hồ sơ PROCESSING quá lâu về hàng đợi. */
    @Modifying
    @Query("update StudentVerification v set v.status = com.metroticket_api.student.StudentVerificationStatus.PENDING, "
            + "v.aiLockedAt = null, v.aiNextAttemptAt = :now "
            + "where v.status = com.metroticket_api.student.StudentVerificationStatus.PROCESSING and v.aiLockedAt < :staleBefore")
    int releaseStale(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    Optional<StudentVerification> findFirstByUserIdOrderBySubmittedAtDesc(UUID userId);

    List<StudentVerification> findByUserIdOrderBySubmittedAtDesc(UUID userId);

    @Query("select v from StudentVerification v where v.userId = :userId "
            + "and v.status = com.metroticket_api.student.StudentVerificationStatus.APPROVED and v.approvedUntil >= :today "
            + "order by v.approvedUntil desc")
    List<StudentVerification> findActiveApproval(@Param("userId") UUID userId, @Param("today") LocalDate today);

    boolean existsByUserIdAndStatusIn(UUID userId, List<StudentVerificationStatus> statuses);

    long countByUserIdAndSubmittedAtAfter(UUID userId, Instant after);

    Page<StudentVerification> findByStatusOrderBySubmittedAtAsc(StudentVerificationStatus status, Pageable pageable);

    Page<StudentVerification> findAllByOrderBySubmittedAtDesc(Pageable pageable);

    @Query("select v from StudentVerification v where v.imagePurgedAt is null and v.decidedAt is not null and v.decidedAt < :before")
    List<StudentVerification> findImagesToPurge(@Param("before") Instant before);
}
