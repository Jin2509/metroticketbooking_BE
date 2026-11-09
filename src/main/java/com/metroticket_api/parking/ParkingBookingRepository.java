package com.metroticket_api.parking;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ParkingBookingRepository extends JpaRepository<ParkingBooking, UUID> {

    /** Số chỗ bị giữ trong khoảng [from, to]: xe đang gửi, hoặc lượt chưa gửi có thời gian giao với khoảng này. */
    @Query("""
            select count(b) from ParkingBooking b
            where b.lot.id = :lotId and b.vehicleType = :type
              and (b.status = com.metroticket_api.parking.ParkingBookingStatus.CHECKED_IN
                   or (b.status in (com.metroticket_api.parking.ParkingBookingStatus.PENDING_PAYMENT,
                                    com.metroticket_api.parking.ParkingBookingStatus.PAID)
                       and b.startAt <= :to and b.endAt >= :from))
            """)
    long countHolding(Long lotId, VehicleType type, Instant from, Instant to);

    @Query("""
            select count(b) > 0 from ParkingBooking b
            where b.plateNumber = :plate
              and (b.status = com.metroticket_api.parking.ParkingBookingStatus.CHECKED_IN
                   or (b.status in (com.metroticket_api.parking.ParkingBookingStatus.PENDING_PAYMENT,
                                    com.metroticket_api.parking.ParkingBookingStatus.PAID)
                       and b.endAt > :now))
            """)
    boolean existsOpenForPlate(String plate, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ParkingBooking b join fetch b.lot l join fetch l.station where b.qrToken = :token")
    Optional<ParkingBooking> findByQrTokenForUpdate(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ParkingBooking b where b.id = :id")
    Optional<ParkingBooking> findByIdForUpdate(UUID id);

    @Query(value = "select b from ParkingBooking b join fetch b.lot where b.userId = :userId order by b.createdAt desc",
            countQuery = "select count(b) from ParkingBooking b where b.userId = :userId")
    Page<ParkingBooking> findMine(UUID userId, Pageable pageable);
}
