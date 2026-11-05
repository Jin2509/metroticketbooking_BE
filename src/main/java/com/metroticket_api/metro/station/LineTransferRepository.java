package com.metroticket_api.metro.station;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LineTransferRepository extends JpaRepository<LineTransfer, Long> {

    List<LineTransfer> findByStationId(Long stationId);

    List<LineTransfer> findByFromLineIdOrToLineId(Long fromLineId, Long toLineId);
}
