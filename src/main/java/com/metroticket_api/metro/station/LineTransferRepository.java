package com.metroticket_api.metro.station;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LineTransferRepository extends JpaRepository<LineTransfer, Long> {

    @Query("select lt from LineTransfer lt join fetch lt.station s join fetch lt.fromLine fl join fetch lt.toLine tl where s.id = :stationId")
    List<LineTransfer> findByStationId(@Param("stationId") Long stationId);

    @Query("select lt from LineTransfer lt join fetch lt.station s join fetch lt.fromLine fl join fetch lt.toLine tl where fl.id = :fromLineId or tl.id = :toLineId")
    List<LineTransfer> findByFromLineIdOrToLineId(@Param("fromLineId") Long fromLineId, @Param("toLineId") Long toLineId);
}
