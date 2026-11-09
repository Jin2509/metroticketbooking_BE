package com.metroticket_api.ticket;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketProductRepository extends JpaRepository<TicketProduct, Long> {

    Optional<TicketProduct> findByCode(String code);

    boolean existsByCode(String code);

    List<TicketProduct> findByStatusOrderByIdAsc(ProductStatus status);

    List<TicketProduct> findAllByOrderByIdAsc();

    Optional<TicketProduct> findByDurationUnitAndDurationValueAndStatusAndSingleJourneyFalse(DurationUnit unit, int value, ProductStatus status);
}
