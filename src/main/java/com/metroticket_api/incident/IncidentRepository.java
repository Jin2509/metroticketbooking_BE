package com.metroticket_api.incident;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Incident i where i.id = :id")
    Optional<Incident> findByIdForUpdate(Long id);

    @Query(value = "select i from Incident i join fetch i.station order by i.createdAt desc",
            countQuery = "select count(i) from Incident i")
    Page<Incident> findNewestFirst(Pageable pageable);

    @Query(value = "select i from Incident i join fetch i.station where i.status = :status order by i.createdAt desc",
            countQuery = "select count(i) from Incident i where i.status = :status")
    Page<Incident> findByStatusNewestFirst(Incident.Status status, Pageable pageable);

    @Query(value = "select i from Incident i join fetch i.station where i.reportedBy = :userId order by i.createdAt desc",
            countQuery = "select count(i) from Incident i where i.reportedBy = :userId")
    Page<Incident> findMine(UUID userId, Pageable pageable);
}
