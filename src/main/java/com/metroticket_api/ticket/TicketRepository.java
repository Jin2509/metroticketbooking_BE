package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    /** Khoá dòng vé khi soát: 2 cổng quẹt cùng lúc sẽ xử lý tuần tự, không thể cùng vào. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.qrToken = :qrToken")
    Optional<Ticket> findByQrTokenForUpdate(@Param("qrToken") String qrToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") UUID id);

    Optional<Ticket> findByQrToken(String qrToken);

    boolean existsByProductId(Long productId);

    Page<Ticket> findByUserIdOrderByPurchasedAtDesc(UUID userId, Pageable pageable);

    Page<Ticket> findByUserIdAndStatusOrderByPurchasedAtDesc(UUID userId, TicketStatus status, Pageable pageable);

    /** Vé chưa vào cổng đã quá hạn. */
    @Modifying
    @Query("update Ticket t set t.status = com.metroticket_api.ticket.TicketStatus.EXPIRED, t.version = t.version + 1 "
            + "where t.status = com.metroticket_api.ticket.TicketStatus.ACTIVE and t.validUntil <= :now")
    int expireUnused(@Param("now") Instant now);

    /** Vé kẹt ở IN_USE (khách không quẹt ra, cổng lỗi) quá lâu sau hạn dùng. */
    @Modifying
    @Query("update Ticket t set t.status = com.metroticket_api.ticket.TicketStatus.EXPIRED, t.version = t.version + 1 "
            + "where t.status = com.metroticket_api.ticket.TicketStatus.IN_USE and t.validUntil <= :cutoff")
    int expireStuckInUse(@Param("cutoff") Instant cutoff);
}
