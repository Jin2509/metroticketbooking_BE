package com.metroticket_api.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByEventTimeDesc(Pageable pageable);

    Page<AuditLog> findByActionOrderByEventTimeDesc(String action, Pageable pageable);
}
