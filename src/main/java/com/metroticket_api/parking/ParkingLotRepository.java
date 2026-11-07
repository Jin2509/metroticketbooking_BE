package com.metroticket_api.parking;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingLotRepository extends JpaRepository<ParkingLot, Long> {

    boolean existsByCode(String code);

    Optional<ParkingLot> findByCode(String code);

    @Query("select l from ParkingLot l join fetch l.station s order by l.code")
    List<ParkingLot> findAllWithStation();

    @Query("select l from ParkingLot l join fetch l.station s where s.id = :stationId order by l.code")
    List<ParkingLot> findByStationWithStation(@Param("stationId") Long stationId);
}
