package com.metroticket_api.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuthEventRepository extends JpaRepository<AuthEvent, UUID>, JpaSpecificationExecutor<AuthEvent> {

    Page<AuthEvent> findByUserId(UUID userId, Pageable pageable);

    @Modifying
    @Query("delete from AuthEvent e where e.createdAt < :before")
    int deleteOlderThan(Instant before);
}
