package com.metroticket_api.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketProductAuditRepository extends JpaRepository<TicketProductAudit, Long> {

    List<TicketProductAudit> findByProductIdOrderByChangedAtDescIdDesc(Long productId);
}
