package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketValidationRepository extends JpaRepository<TicketValidation, Long> {

    @Query("select v from TicketValidation v join fetch v.station where v.ticket.id = :ticketId order by v.validatedAt desc")
    List<TicketValidation> findByTicketId(@Param("ticketId") UUID ticketId);

    /** Vé đã được soát thành công tại ga trong khoảng thời gian (dùng cho ưu đãi Park & Ride). */
    @Query("select count(v) from TicketValidation v where v.ticket.id = :ticketId and v.station.id = :stationId "
            + "and v.result = com.metroticket_api.ticket.GateResult.ACCEPTED and v.validatedAt between :from and :to")
    long countAcceptedAtStationBetween(@Param("ticketId") UUID ticketId, @Param("stationId") Long stationId,
            @Param("from") Instant from, @Param("to") Instant to);
}
