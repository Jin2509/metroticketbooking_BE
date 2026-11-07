package com.metroticket_api.parking;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingSessionRepository extends JpaRepository<ParkingSession, UUID> {

    long countByLotIdAndVehicleTypeAndStatus(Long lotId, VehicleType vehicleType, ParkingSessionStatus status);

    long countByMetroTicketIdAndCheckOutAtGreaterThanEqualAndCheckOutAtLessThan(UUID ticketId,
            java.time.Instant from, java.time.Instant to);

    boolean existsByPlateNumberAndStatus(String plateNumber, ParkingSessionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ParkingSession s where s.sessionCode = :code")
    Optional<ParkingSession> findByCodeForUpdate(@Param("code") String code);

    Optional<ParkingSession> findBySessionCode(String code);

    Page<ParkingSession> findByLotIdAndStatusOrderByCheckInAtDesc(Long lotId, ParkingSessionStatus status, Pageable pageable);

    Page<ParkingSession> findByLotIdOrderByCheckInAtDesc(Long lotId, Pageable pageable);

    Page<ParkingSession> findByMonthlyPassUserIdOrderByCheckInAtDesc(UUID userId, Pageable pageable);

    @Query("select s.lot.id, s.vehicleType, count(s) from ParkingSession s "
            + "where s.status = com.metroticket_api.parking.ParkingSessionStatus.PARKED and s.lot.id in :lotIds "
            + "group by s.lot.id, s.vehicleType")
    List<Object[]> countParkedByLot(@Param("lotIds") Collection<Long> lotIds);
}
