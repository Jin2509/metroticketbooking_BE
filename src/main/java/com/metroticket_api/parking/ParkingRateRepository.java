package com.metroticket_api.parking;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingRateRepository extends JpaRepository<ParkingRate, Long> {

    Optional<ParkingRate> findByLotIdAndVehicleType(Long lotId, VehicleType vehicleType);

    /** Khoá dòng sức chứa: các lượt xe vào cùng loại xe cùng bãi được xử lý tuần tự nên không vượt sức chứa. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ParkingRate r where r.lot.id = :lotId and r.vehicleType = :vehicleType")
    Optional<ParkingRate> findForUpdate(@Param("lotId") Long lotId, @Param("vehicleType") VehicleType vehicleType);

    List<ParkingRate> findByLotIdIn(Collection<Long> lotIds);
}
