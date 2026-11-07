package com.metroticket_api.metro.staff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metroticket_api.metro.common.TransferStatus;

public interface StaffStationRepository extends JpaRepository<StaffStation, Long> {

    boolean existsByUserIdAndStationIdAndStatus(UUID userId, Long stationId, TransferStatus status);

    Optional<StaffStation> findByUserIdAndStationIdAndStatus(UUID userId, Long stationId, TransferStatus status);

    @Query("select ss from StaffStation ss join fetch ss.station s where s.id = :stationId and ss.status = :status order by ss.assignedAt")
    List<StaffStation> findByStation(@Param("stationId") Long stationId, @Param("status") TransferStatus status);

    @Query("select ss from StaffStation ss join fetch ss.station s where ss.userId = :userId and ss.status = :status order by s.code")
    List<StaffStation> findByUser(@Param("userId") UUID userId, @Param("status") TransferStatus status);
}
