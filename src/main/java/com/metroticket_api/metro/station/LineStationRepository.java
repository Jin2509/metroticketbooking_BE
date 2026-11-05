package com.metroticket_api.metro.station;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LineStationRepository extends JpaRepository<LineStation, Long> {

    List<LineStation> findByLineIdOrderByStationOrderAsc(Long lineId);

    List<LineStation> findByStationIdOrderByStationOrderAsc(Long stationId);

    List<LineStation> findByStationId(Long stationId);

    boolean existsByLineIdAndStationId(Long lineId, Long stationId);

    void deleteByLineIdAndStationId(Long lineId, Long stationId);
}
