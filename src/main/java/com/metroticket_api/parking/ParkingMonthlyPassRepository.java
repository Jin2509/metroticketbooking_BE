package com.metroticket_api.parking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingMonthlyPassRepository extends JpaRepository<ParkingMonthlyPass, UUID> {

    Optional<ParkingMonthlyPass> findByPassCode(String passCode);

    List<ParkingMonthlyPass> findByUserIdOrderByValidUntilDesc(UUID userId);

    @Query("select count(p) from ParkingMonthlyPass p where p.lot.id = :lotId and p.plateNumber = :plate "
            + "and p.status = com.metroticket_api.parking.MonthlyPassStatus.ACTIVE and p.validUntil > :from and p.validFrom < :to")
    long countOverlapping(@Param("lotId") Long lotId, @Param("plate") String plate, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("select count(p) from ParkingMonthlyPass p where p.lot.id = :lotId and p.vehicleType = :type "
            + "and p.status = com.metroticket_api.parking.MonthlyPassStatus.ACTIVE and p.validUntil > :now")
    long countActive(@Param("lotId") Long lotId, @Param("type") VehicleType type, @Param("now") Instant now);
}
