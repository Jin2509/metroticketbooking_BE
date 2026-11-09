package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TicketPriceRepository extends JpaRepository<TicketPrice, Long> {

    boolean existsByProductIdAndStatusIn(Long productId, List<TicketPrice.Status> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TicketPrice p join fetch p.product where p.id = :id")
    Optional<TicketPrice> findByIdForUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TicketPrice p join fetch p.product where p.status = :status and p.effectiveFrom <= :at")
    List<TicketPrice> findDueForUpdate(TicketPrice.Status status, Instant at);

    @Query(value = "select p from TicketPrice p join fetch p.product order by p.submittedAt desc, p.id desc",
            countQuery = "select count(p) from TicketPrice p")
    Page<TicketPrice> findAllNewestFirst(Pageable pageable);

    @Query(value = "select p from TicketPrice p join fetch p.product where p.status = :status order by p.submittedAt desc, p.id desc",
            countQuery = "select count(p) from TicketPrice p where p.status = :status")
    Page<TicketPrice> findByStatusNewestFirst(TicketPrice.Status status, Pageable pageable);
}
